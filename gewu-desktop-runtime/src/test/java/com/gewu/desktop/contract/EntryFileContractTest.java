package com.gewu.desktop.contract;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口调度文件域契约测试（设计文档 56 §7.2）：resolve 预览 / 读回显 / 写保存.
 * 项目层用 @TempDir 隔离；无版本头的项目文件在任意全局层状态下必然胜出
 * （§5.2 规则 2b），断言确定性不受测试机 ~/.gewu 现状影响。
 *
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EntryFileContractTest {

    @Autowired
    private TestRestTemplate rest;

    @TempDir
    static Path tempProject;

    @Test
    @Order(1)
    void registerProjectAndSaveRead() {
        // 目录限定（CR L3-SEC-006 相邻）：项目层读写仅允许已注册目录，先注册绑定
        Map created = post("/api/v1/projects", Map.of(
                "name", "契约测试项目", "directory", tempProject.toString()));
        assertThat(created.get("code")).isEqualTo(10000);

        Map<String, String> save = Map.of(
                "scope", "project",
                "name", "AGENTS.md",
                "project", tempProject.toString(),
                "content", "# 契约测试项目规范\n\n构建命令: mvn test");
        Map body = post("/api/v1/entry-file", save);
        assertThat(body.get("code")).isEqualTo(10000);

        Map read = rest.getForObject(
                "/api/v1/entry-file?scope=project&name=AGENTS.md&project=" + tempProject, Map.class);
        assertThat(read.get("code")).isEqualTo(10000);
        Map data = (Map) read.get("data");
        assertThat(data.get("exists")).isEqualTo(true);
        assertThat((String) data.get("content")).contains("契约测试项目规范");
    }

    @Test
    @Order(2)
    void resolvePicksProjectEntry_unversionedNotOverridden() throws Exception {
        // 已由 Order(1) 写入无版本头 AGENTS.md → 无论测试机全局层状态如何都项目级胜
        Map body = rest.getForObject(
                "/api/v1/entry-file/resolve?project=" + tempProject, Map.class);
        assertThat(body.get("code")).isEqualTo(10000);
        Map data = (Map) body.get("data");
        assertThat(data.get("present")).isEqualTo(true);
        assertThat(String.valueOf(data.get("source"))).startsWith("project:");
        assertThat((String) data.get("content")).contains("契约测试项目规范");
        assertThat(String.valueOf(data.get("meta"))).contains("\"reason\"");
    }

    @Test
    @Order(3)
    void illegalName_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity =
                new HttpEntity<>(Map.of("scope", "global", "name", "../evil.md", "content", "x"), headers);
        var resp = rest.exchange("/api/v1/entry-file", HttpMethod.POST, entity, Map.class);
        assertThat(resp.getStatusCode().isError()).isTrue();
    }

    private Map post(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForObject(url, new HttpEntity<>(body, headers), Map.class);
    }
}
