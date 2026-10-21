package com.gewu.common.entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntryFileSelector} 表驱动单测（设计文档 56 §5.2/§11.1）.
 */
class EntryFileSelectorTest {

    private EntryCandidate global(String version, String content) {
        return EntryCandidate.builder()
                .level("global").path("user-global:GEWU.md").version(version).content(content).build();
    }

    private EntryCandidate project(String version, String content) {
        return EntryCandidate.builder()
                .level("project").path("project:AGENTS.md").version(version).content(content).build();
    }

    @Test
    void bothAbsent_none() {
        EntrySelection s = EntryFileSelector.select(null, null);
        assertThat(s.getSource()).isEqualTo("none");
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_NO_ENTRY);
        assertThat(s.getContent()).isNull();
    }

    @Test
    void onlyOne_exists() {
        EntrySelection onlyGlobal = EntryFileSelector.select(global("1.0.0", "g"), null);
        assertThat(onlyGlobal.getReason()).isEqualTo(EntryFileSelector.REASON_ONLY_CANDIDATE);
        assertThat(onlyGlobal.getContent()).isEqualTo("g");

        EntrySelection onlyProject = EntryFileSelector.select(null, project(null, "p"));
        assertThat(onlyProject.getReason()).isEqualTo(EntryFileSelector.REASON_ONLY_CANDIDATE);
        assertThat(onlyProject.getContent()).isEqualTo("p");
    }

    @Test
    void bothVersioned_higherWins() {
        EntrySelection s = EntryFileSelector.select(global("1.1.0", "g"), project("1.0.0", "p"));
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_VERSION_HIGHER);
        assertThat(s.getContent()).isEqualTo("g");
        assertThat(s.getVersion()).isEqualTo("1.1.0");

        EntrySelection s2 = EntryFileSelector.select(global("1.0.0", "g"), project("2.3.1", "p"));
        assertThat(s2.getReason()).isEqualTo(EntryFileSelector.REASON_VERSION_HIGHER);
        assertThat(s2.getContent()).isEqualTo("p");
    }

    @Test
    void bothVersioned_equal_projectWins() {
        EntrySelection s = EntryFileSelector.select(global("1.3.0", "g"), project("1.3.0", "p"));
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_PROJECT_DEFAULT);
        assertThat(s.getContent()).isEqualTo("p");
    }

    @Test
    void singleSideUnversioned_projectWins_主流就近兜底() {
        // 评审质询修订核心用例：无版本头外部 AGENTS.md 不被带版本全局静默覆盖
        EntrySelection s = EntryFileSelector.select(global("1.1.0", "g"), project(null, "p"));
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_PROJECT_DEFAULT);
        assertThat(s.getContent()).isEqualTo("p");

        EntrySelection s2 = EntryFileSelector.select(global(null, "g"), project("1.0.0", "p"));
        assertThat(s2.getReason()).isEqualTo(EntryFileSelector.REASON_PROJECT_DEFAULT);
        assertThat(s2.getContent()).isEqualTo("p");
    }

    @Test
    void bothUnversioned_projectWins() {
        EntrySelection s = EntryFileSelector.select(global(null, "g"), project(null, "p"));
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_PROJECT_DEFAULT);
        assertThat(s.getContent()).isEqualTo("p");
    }

    @Test
    void illegalVersion_normalizedAsZero() {
        // 非法版本串按 0.0.0 处理 → 等值 → 项目级胜
        EntrySelection s = EntryFileSelector.select(global("abc", "g"), project("1.0.0", "p"));
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_VERSION_HIGHER);
        assertThat(s.getContent()).isEqualTo("p");
    }

    @Test
    void versionNormalization() {
        assertThat(EntryFileSelector.normalizeVersion("1.3")).isEqualTo("1.3.0");
        assertThat(EntryFileSelector.normalizeVersion("v1.3.0")).isEqualTo("1.3.0");
        assertThat(EntryFileSelector.normalizeVersion("V2")).isEqualTo("2.0.0");
        assertThat(EntryFileSelector.normalizeVersion("1.3.0.9")).isEqualTo("1.3.0");
        assertThat(EntryFileSelector.compareVersions("1.3", "1.3.0")).isZero();
        assertThat(EntryFileSelector.compareVersions("1.3", "1.3.1")).isNegative();
        assertThat(EntryFileSelector.compareVersions("0.9", "1.0.0")).isNegative();
    }

    @Test
    @DisplayName("MERGE 叠加：双侧内容拼接（reason=merged）")
    void mergeLayering_concatenatesBoth() {
        EntrySelection s = EntryFileSelector.select(global("1.1.0", "全局段"), project("1.0.0", "项目段"),
                EntryFileSelector.Layering.MERGE, false);
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_MERGED);
        assertThat(s.getContent()).contains("全局段").contains("项目段");
        assertThat(s.getSource()).contains("+");
    }

    @Test
    @DisplayName("MERGE + override:true → 项目级独占（project-override）")
    void mergeLayering_projectOverride() {
        EntrySelection s = EntryFileSelector.select(global("1.1.0", "全局段"), project("1.0.0", "项目段"),
                EntryFileSelector.Layering.MERGE, true);
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_PROJECT_OVERRIDE);
        assertThat(s.getContent()).isEqualTo("项目段");
    }

    @Test
    @DisplayName("MERGE 下单侧缺失 → 退化为 only-candidate")
    void mergeLayering_singleSide() {
        EntrySelection s = EntryFileSelector.select(global("1.1.0", "全局段"), null,
                EntryFileSelector.Layering.MERGE, false);
        assertThat(s.getReason()).isEqualTo(EntryFileSelector.REASON_ONLY_CANDIDATE);
    }

    @Test
    @DisplayName("override frontmatter 解析")
    void extractOverride() {
        assertThat(EntryFileSelector.extractOverride("---\noverride: true\nversion: 1.0.0\n---\n")).isTrue();
        assertThat(EntryFileSelector.extractOverride("---\noverride: false\n---\n")).isFalse();
        assertThat(EntryFileSelector.extractOverride("# 无 frontmatter")).isFalse();
    }

    @Test
    void extractVersion_frontmatter() {
        String md = "---\ndescription: 规范\nversion: 1.3.0\n---\n\n# 正文";
        assertThat(EntryFileSelector.extractVersion(md)).isEqualTo("1.3.0");
    }

    @Test
    void extractVersion_commentMarker() {
        String md = "<!-- gewu-entry: v1.3.0 -->\n# 项目规范";
        assertThat(EntryFileSelector.extractVersion(md)).isEqualTo("1.3.0");
    }

    @Test
    void extractVersion_absentOrNull() {
        assertThat(EntryFileSelector.extractVersion("# 无版本头的标准 AGENTS.md\n\n内容")).isNull();
        assertThat(EntryFileSelector.extractVersion(null)).isNull();
        assertThat(EntryFileSelector.extractVersion("version: abc")).isNull();
        // version 出现在 frontmatter 之外不误伤
        assertThat(EntryFileSelector.extractVersion("# 标题\nversion: 9.9.9")).isNull();
    }
}
