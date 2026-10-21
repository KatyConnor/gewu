package com.gewu.common.entry;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntryImportExpander} 单测（设计文档 56 §10 P2）.
 */
class EntryImportExpanderTest {

    private EntryImportExpander.ImportReader readerOf(Map<String, String> files) {
        return (baseDir, rel) -> files.get(rel);
    }

    @Test
    void expandsWholeLineImport() {
        Map<String, String> files = Map.of("docs/style.md", "# 代码风格\n- 使用中文注释");
        String content = "# 入口\n\n@docs/style.md\n\n## 其他\n正文";
        String out = EntryImportExpander.expand(content, "/base", readerOf(files));
        assertThat(out).contains("<!-- import: docs/style.md -->")
                .contains("# 代码风格")
                .contains("## 其他");
    }

    @Test
    void missingImport_keepsLineWithMark() {
        String content = "# 入口\n\n@docs/missing.md\n";
        String out = EntryImportExpander.expand(content, "/base", readerOf(Map.of()));
        assertThat(out).contains("@docs/missing.md").contains("import 未找到");
    }

    @Test
    void maxImports_capped() {
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder content = new StringBuilder("# 入口\n");
        for (int i = 1; i <= 8; i++) {
            files.put("f" + i + ".md", "内容" + i);
            content.append("@f").append(i).append(".md\n");
        }
        String out = EntryImportExpander.expand(content.toString(), "/base", readerOf(files));
        assertThat(out).contains("内容1").contains("内容5").doesNotContain("内容6");
        assertThat(out).contains("@f6.md");
    }

    @Test
    void duplicateImport_expandedOnce() {
        Map<String, String> files = Map.of("a.md", "AAA");
        String out = EntryImportExpander.expand("# 入口\n@a.md\n@a.md\n", "/base", readerOf(files));
        assertThat(out).contains("AAA");
        // 开标记只出现一次（第二个重复 import 保留原行）；闭标记配对出现
        assertThat(out.split("<!-- import: a.md -->").length - 1).isEqualTo(1);
        assertThat(out.split("<!-- /import: a.md -->").length - 1).isEqualTo(1);
        assertThat(out).contains("@a.md");
    }

    @Test
    void nonImportAtSign_notExpanded() {
        Map<String, String> files = Map.of("a.md", "AAA");
        String out = EntryImportExpander.expand("联系 @张三 或邮箱 a@b.com\n@a.md b.md\n", "/base", readerOf(files));
        // @张三 / a@b.com 不构成整行 import；@a.md b.md 含空格也不是
        assertThat(out).doesNotContain("import:").contains("@张三").contains("a@b.com");
    }

    @Test
    void pathTraversalRejected() {
        String out = EntryImportExpander.expand("@../etc/passwd\n", "/base", readerOf(Map.of()));
        assertThat(out).doesNotContain("import:").contains("@../etc/passwd");
    }

    @Test
    void noAtSign_passthrough() {
        String content = "# 纯文本\n无 import";
        assertThat(EntryImportExpander.expand(content, "/base", readerOf(Map.of()))).isSameAs(content);
    }
}
