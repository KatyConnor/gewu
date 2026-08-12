package com.gewu.application.wenshi.output;

import com.gewu.application.wenshi.reasoning.WenshiReasoningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContentClassifier 内容分类判定单元测试。
 *
 * @since 1.0.0
 */
@DisplayName("内容分类判定测试")
class ContentClassifierTest {

    private final ContentClassifier classifier = new ContentClassifier();

    @Test
    @DisplayName("短文本无代码块 -> DIRECT 直接输出")
    void classify_shortTextNoCode_direct() {
        OutputDecision decision = classifier.classify("这是一个简单的回答。", null);

        assertEquals(OutputDecision.DecisionType.DIRECT, decision.getType());
        assertEquals("这是一个简单的回答。", decision.getDisplayContent());
        assertNull(decision.getFiles());
    }

    @Test
    @DisplayName("空内容 -> DIRECT")
    void classify_empty_direct() {
        OutputDecision decision = classifier.classify("", null);
        assertEquals(OutputDecision.DecisionType.DIRECT, decision.getType());
    }

    @Test
    @DisplayName("Planner 标注 FILE_OUTPUT -> 强制输出文件")
    void classify_plannerFlagged_fileOutput() {
        WenshiReasoningResult.SubgoalNode subgoal = WenshiReasoningResult.SubgoalNode.builder()
                .id("s1").description("生成代码").strategy("FILE_OUTPUT").build();

        OutputDecision decision = classifier.classify("一些内容", List.of(subgoal));

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertNotNull(decision.getFiles());
        assertFalse(decision.getFiles().isEmpty());
    }

    @Test
    @DisplayName("长代码块超过50行 -> FILE_OUTPUT")
    void classify_longCodeBlock_fileOutput() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            code.append("    System.out.println(\"line ").append(i).append("\");\n");
        }
        String answer = "```java\n" + code + "```";

        OutputDecision decision = classifier.classify(answer, null);

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertEquals(1, decision.getFiles().size());
        assertEquals("code", decision.getFiles().get(0).getFileType());
        assertEquals("java", decision.getFiles().get(0).getLanguage());
    }

    @Test
    @DisplayName("短代码块不超过50行 -> DIRECT")
    void classify_shortCodeBlock_direct() {
        String answer = "```java\nSystem.out.println(\"hello\");\n```";

        OutputDecision decision = classifier.classify(answer, null);

        assertEquals(OutputDecision.DecisionType.DIRECT, decision.getType());
    }

    @Test
    @DisplayName("长文档超过2000字符+多级标题 -> FILE_OUTPUT 存为 Markdown")
    void classify_longDocumentWithHeaders_fileOutput() {
        StringBuilder doc = new StringBuilder();
        doc.append("# 一级标题\n\n");
        doc.append("## 二级标题\n\n");
        doc.append("### 三级标题\n\n");
        for (int i = 0; i < 500; i++) {
            doc.append("这是正文内容行").append(i).append("。\n");
        }

        OutputDecision decision = classifier.classify(doc.toString(), null);

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertEquals(1, decision.getFiles().size());
        assertEquals("markdown", decision.getFiles().get(0).getFileType());
    }

    @Test
    @DisplayName("长文本无结构化标记 -> DIRECT（仅长不够触发）")
    void classify_longTextNoStructure_direct() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            text.append("这是一段没有结构化标记的纯文本内容行").append(i).append("。");
        }

        OutputDecision decision = classifier.classify(text.toString(), null);

        assertEquals(OutputDecision.DecisionType.DIRECT, decision.getType());
    }

    @Test
    @DisplayName("多个长代码块各自独立存为文件")
    void classify_multipleCodeBlocks_multipleFiles() {
        StringBuilder code1 = new StringBuilder();
        for (int i = 0; i < 60; i++) code1.append("print(").append(i).append(")\n");
        StringBuilder code2 = new StringBuilder();
        for (int i = 0; i < 55; i++) code2.append("console.log(").append(i).append(")\n");

        String answer = "```python\n" + code1 + "```\n\n```typescript\n" + code2 + "```";

        OutputDecision decision = classifier.classify(answer, null);

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertEquals(2, decision.getFiles().size());
        assertEquals("python", decision.getFiles().get(0).getLanguage());
        assertEquals("typescript", decision.getFiles().get(1).getLanguage());
    }

    @Test
    @DisplayName("代码块提取：正确识别语言和行数")
    void extractCodeBlocks_correctLanguageAndLines() {
        String text = "```java\nline1\nline2\nline3\n```\n\n```python\na\nb\n```";

        List<ContentClassifier.CodeBlock> blocks = classifier.extractCodeBlocks(text);

        assertEquals(2, blocks.size());
        assertEquals("java", blocks.get(0).language());
        assertEquals(3, blocks.get(0).lineCount());
        assertEquals("python", blocks.get(1).language());
        assertEquals(2, blocks.get(1).lineCount());
    }

    @Test
    @DisplayName("表格行数统计")
    void countTableRows_correct() {
        String text = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n普通文本";
        assertEquals(4, classifier.countTableRows(text));
    }

    @Test
    @DisplayName("标题层级统计")
    void countHeaderLevels_correct() {
        assertEquals(3, classifier.countHeaderLevels("# H1\n## H2\n### H3\n正文"));
        assertEquals(1, classifier.countHeaderLevels("# 仅一级"));
        assertEquals(0, classifier.countHeaderLevels("无标题的文本"));
    }

    @Test
    @DisplayName("子目标描述含 Word 关键词 -> 文件类型为 docx")
    void classify_wordKeyword_docxFileType() {
        StringBuilder doc = new StringBuilder();
        doc.append("# 技术方案\n\n## 概述\n\n### 详情\n\n");
        for (int i = 0; i < 500; i++) {
            doc.append("正文内容行").append(i).append("。\n");
        }

        WenshiReasoningResult.SubgoalNode subgoal = WenshiReasoningResult.SubgoalNode.builder()
                .id("s1").description("生成Word文档内容").strategy("FILE_OUTPUT").build();

        OutputDecision decision = classifier.classify(doc.toString(), List.of(subgoal));

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertEquals(1, decision.getFiles().size());
        assertEquals("docx", decision.getFiles().get(0).getFileType());
    }

    @Test
    @DisplayName("子目标描述无 Word 关键词 -> 文件类型为 markdown")
    void classify_noWordKeyword_markdownFileType() {
        StringBuilder doc = new StringBuilder();
        doc.append("# 技术方案\n\n## 概述\n\n### 详情\n\n");
        for (int i = 0; i < 500; i++) {
            doc.append("正文内容行").append(i).append("。\n");
        }

        WenshiReasoningResult.SubgoalNode subgoal = WenshiReasoningResult.SubgoalNode.builder()
                .id("s1").description("生成文档内容").strategy("FILE_OUTPUT").build();

        OutputDecision decision = classifier.classify(doc.toString(), List.of(subgoal));

        assertEquals(OutputDecision.DecisionType.FILE_OUTPUT, decision.getType());
        assertEquals("markdown", decision.getFiles().get(0).getFileType());
    }
}