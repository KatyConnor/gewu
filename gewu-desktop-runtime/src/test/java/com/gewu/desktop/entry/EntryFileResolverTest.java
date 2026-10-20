package com.gewu.desktop.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.desktop.config.DesktopProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntryFileResolver} 单测：发现顺序/跨层裁决/上限截断/meta 结构.
 * 通过临时 user.home 隔离全局层（还原于 @AfterEach），项目层用 @TempDir。
 */
class EntryFileResolverTest {

    @TempDir
    Path tempHome;

    @TempDir
    Path tempProject;

    private String originalHome;
    private EntryFileResolver resolver;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        resolver = new EntryFileResolver(new DesktopProps(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    private Path gewuDir() throws Exception {
        Path dir = tempHome.resolve(".gewu");
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void noFiles_none() throws Exception {
        var r = resolver.resolve(tempProject.toString());
        assertThat(r.present()).isFalse();
        assertThat(r.source()).isEqualTo("none");
    }

    @Test
    void projectOnly_projectWins() throws Exception {
        Files.writeString(tempProject.resolve("AGENTS.md"), "# 项目规范", StandardCharsets.UTF_8);
        var r = resolver.resolve(tempProject.toString());
        assertThat(r.present()).isTrue();
        assertThat(r.source()).isEqualTo("project:AGENTS.md");
        assertThat(r.content()).contains("项目规范");
        assertThat(r.metaJson()).contains("\"reason\":\"only-candidate\"");
    }

    @Test
    void gewuPreferredOverAgents_sameLayer() throws Exception {
        Files.writeString(gewuDir().resolve("GEWU.md"), "格物专属", StandardCharsets.UTF_8);
        Files.writeString(gewuDir().resolve("AGENTS.md"), "通用规范", StandardCharsets.UTF_8);
        var r = resolver.resolve(tempProject.toString());
        assertThat(r.source()).isEqualTo("user-global:GEWU.md");
        assertThat(r.content()).contains("格物专属");
    }

    @Test
    void bothVersioned_globalHigherWins() throws Exception {
        Files.writeString(gewuDir().resolve("GEWU.md"),
                "---\nversion: 2.0.0\n---\n全局治理模板", StandardCharsets.UTF_8);
        Files.writeString(tempProject.resolve("GEWU.md"),
                "<!-- gewu-entry: v1.0.0 -->\n项目自有规范", StandardCharsets.UTF_8);
        var r = resolver.resolve(tempProject.toString());
        assertThat(r.source()).isEqualTo("user-global:GEWU.md");
        assertThat(r.metaJson()).contains("\"reason\":\"version-higher\"");
    }

    @Test
    void projectUnversionedNotOverridden_评审质询修订核心语义() throws Exception {
        // 无版本头的项目 AGENTS.md 不被带版本的全局文件静默覆盖
        Files.writeString(gewuDir().resolve("GEWU.md"),
                "---\nversion: 9.9.9\n---\n全局模板", StandardCharsets.UTF_8);
        Files.writeString(tempProject.resolve("AGENTS.md"), "# 导入的外部规范", StandardCharsets.UTF_8);
        var r = resolver.resolve(tempProject.toString());
        assertThat(r.source()).isEqualTo("project:AGENTS.md");
        assertThat(r.content()).contains("导入的外部规范");
        assertThat(r.metaJson()).contains("\"reason\":\"project-default\"");
    }

    @Test
    void injectCap_truncatesAndMarks() throws Exception {
        DesktopProps props = new DesktopProps();
        props.setEntryMaxBytes(32);
        EntryFileResolver capped = new EntryFileResolver(props, new ObjectMapper());
        StringBuilder big = new StringBuilder("# 大文件\n");
        while (big.toString().getBytes(StandardCharsets.UTF_8).length < 200) {
            big.append("格物平台入口调度指令测试内容。");
        }
        Files.writeString(tempProject.resolve("GEWU.md"), big.toString(), StandardCharsets.UTF_8);
        var r = capped.resolve(tempProject.toString());
        assertThat(r.present()).isTrue();
        assertThat(r.metaJson()).contains("\"truncated\":true");
        assertThat(r.content()).endsWith("…（入口调度指令超限截断）");
    }

    @Test
    void readCap_64KB() throws Exception {
        StringBuilder huge = new StringBuilder();
        while (huge.toString().getBytes(StandardCharsets.UTF_8).length < 80 * 1024) {
            huge.append("x");
        }
        Files.writeString(tempProject.resolve("AGENTS.md"), huge.toString(), StandardCharsets.UTF_8);
        var r = resolver.resolve(tempProject.toString());
        // 80KB 源文件：读取上限 64KB 截断后，再按 32KB 注入上限截断并追加说明行——
        // 最终内容必然远小于原文且带截断标记
        assertThat(r.metaJson()).contains("\"truncated\":true");
        assertThat(r.content().getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(80 * 1024);
    }
}
