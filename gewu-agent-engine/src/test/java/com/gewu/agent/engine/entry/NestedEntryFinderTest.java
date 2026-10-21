package com.gewu.agent.engine.entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NestedEntryFinder} 就近发现单测（设计文档 57 §3/§8）.
 */
class NestedEntryFinderTest {

    private NestedEntryFinder.Probe probeOf(Map<String, String> files) {
        return (dir, name) -> files.get(dir + "/" + name);
    }

    @Test
    @DisplayName("多级命中：packages/foo 子目录入口文件生效")
    void nestedHit() {
        Map<String, String> files = Map.of("packages/foo/AGENTS.md", "foo 子项目约定");
        var hit = NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(files));
        assertThat(hit).isNotNull();
        assertThat(hit.relativeDir()).isEqualTo("packages/foo");
        assertThat(hit.fileName()).isEqualTo("AGENTS.md");
        assertThat(hit.content()).contains("foo 子项目约定");
    }

    @Test
    @DisplayName("就近优先：深层与浅层并存时命中最近层")
    void nearestWins() {
        Map<String, String> files = Map.of(
                "packages/AGENTS.md", "packages 层约定",
                "packages/foo/AGENTS.md", "foo 层约定");
        var hit = NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(files));
        assertThat(hit.relativeDir()).isEqualTo("packages/foo");
    }

    @Test
    @DisplayName("同层 GEWU.md 优先于 AGENTS.md")
    void gewuPreferred() {
        Map<String, String> files = Map.of(
                "packages/foo/GEWU.md", "格物专属",
                "packages/foo/AGENTS.md", "通用");
        var hit = NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(files));
        assertThat(hit.fileName()).isEqualTo("GEWU.md");
        assertThat(hit.content()).contains("格物专属");
    }

    @Test
    @DisplayName("根层不参与探测（根文件已随 system 注入）")
    void rootExcluded() {
        Map<String, String> files = Map.of("AGENTS.md", "根约定");
        assertThat(NestedEntryFinder.find("", "README.md", probeOf(files))).isNull();
        // 仅根有文件、目标在子目录：向上探测到根即停（不含根），返回 null
        assertThat(NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(files))).isNull();
    }

    @Test
    @DisplayName("越界拒绝：绝对路径与 .. 段返回 null")
    void traversalRejected() {
        assertThat(NestedEntryFinder.find("", "/etc/passwd", probeOf(Map.of()))).isNull();
        assertThat(NestedEntryFinder.find("", "../outside/x.java", probeOf(Map.of()))).isNull();
    }

    @Test
    @DisplayName("无命中与空内容视为缺失")
    void noHitAndEmptyContent() {
        assertThat(NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(Map.of()))).isNull();
        Map<String, String> empty = Map.of("packages/foo/AGENTS.md", "");
        assertThat(NestedEntryFinder.find("", "packages/foo/src/X.java", probeOf(empty))).isNull();
    }

    @Test
    @DisplayName("显式 root 前缀：探测止于 root 层")
    void explicitRootBoundary() {
        Map<String, String> files = Map.of(
                "repo/AGENTS.md", "repo 根约定",
                "repo/packages/foo/AGENTS.md", "foo 约定");
        var hit = NestedEntryFinder.find("repo", "repo/packages/foo/src/X.java", probeOf(files));
        assertThat(hit.relativeDir()).isEqualTo("repo/packages/foo");
        // repo 根层有文件也不触发（根不探测）
        assertThat(NestedEntryFinder.find("repo", "repo/README.md", probeOf(files))).isNull();
    }

    @Test
    @DisplayName("探针异常按缺失处理")
    void probeExceptionTolerated() {
        var hit = NestedEntryFinder.find("", "packages/foo/src/X.java", (dir, name) -> {
            throw new IllegalStateException("沙箱异常");
        });
        assertThat(hit).isNull();
    }
}
