package com.gewu.application.wenshi.output;

import com.gewu.application.wenshi.reasoning.WenshiReasoningResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容分类器 - 混合判定推理结果应直接输出还是保存为文件。
 * <p>
 * 判定逻辑（混合策略）：
 * <ol>
 *   <li>Planner 标注检查：子目标策略含 FILE_OUTPUT -> 强制输出文件</li>
 *   <li>内容特征检查：
 *     <ul>
 *       <li>包含代码块且超过 {@value #MIN_CODE_LINES} 行</li>
 *       <li>总长度超过 {@value #MIN_FILE_LENGTH} 字符且有结构化标记（多级标题/长表格）</li>
 *     </ul>
 *   </li>
 * </ol>
 * 简单问答（短文本无代码块）直接对话框输出，复杂内容拆分为多个文件保存。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class ContentClassifier {

    /** 总字符阈值，超过此长度视为长内容 */
    static final int MIN_FILE_LENGTH = 2000;

    /** 代码块行数阈值，超过此行数的代码块独立存为文件 */
    static final int MIN_CODE_LINES = 50;

    /** 表格行数阈值 */
    static final int MIN_TABLE_ROWS = 10;

    /** 多级标题阈值 */
    static final int MIN_HEADER_LEVELS = 3;

    /** 代码块正则：```language ... ``` */
    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");

    /** 表格行正则（| 开头的行） */
    private static final Pattern TABLE_ROW_PATTERN =
            Pattern.compile("^\\|.*\\|$", Pattern.MULTILINE);

    /** 标题正则（# / ## / ### 等） */
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^(#{1,6})\\s", Pattern.MULTILINE);

    /**
     * 对推理结果进行分类判定。
     *
     * @param answer    完整推理结果文本
     * @param subgoals  推理计划中的子目标列表（用于检查 FILE_OUTPUT 策略标注）
     * @return 判定结果（DIRECT 或 FILE_OUTPUT）
     * @since 1.0.0
     */
    public OutputDecision classify(String answer, List<WenshiReasoningResult.SubgoalNode> subgoals) {
        if (answer == null || answer.isBlank()) {
            return OutputDecision.direct(answer != null ? answer : "");
        }

        // 1. Planner 标注检查
        boolean plannerFlagged = false;
        if (subgoals != null) {
            plannerFlagged = subgoals.stream()
                    .anyMatch(s -> "FILE_OUTPUT".equals(s.getStrategy()));
        }

        // 2. 内容特征检查
        List<CodeBlock> codeBlocks = extractCodeBlocks(answer);
        boolean hasLongCode = codeBlocks.stream().anyMatch(b -> b.lineCount > MIN_CODE_LINES);
        boolean isLong = answer.length() > MIN_FILE_LENGTH;
        int tableRows = countTableRows(answer);
        int headerLevels = countHeaderLevels(answer);
        boolean hasLongTable = tableRows > MIN_TABLE_ROWS;
        boolean hasMultiLevelHeaders = headerLevels >= MIN_HEADER_LEVELS;

        boolean isComplex = plannerFlagged
                || hasLongCode
                || (isLong && (hasLongTable || hasMultiLevelHeaders));

        log.debug("ContentClassifier: plannerFlagged={}, hasLongCode={}, isLong={}, " +
                        "tableRows={}, headerLevels={}, isComplex={}",
                plannerFlagged, hasLongCode, isLong, tableRows, headerLevels, isComplex);

        if (!isComplex) {
            return OutputDecision.direct(answer);
        }

        // 3. 拆分文件
        List<FileArtifact> files = new ArrayList<>();
        for (CodeBlock block : codeBlocks) {
            if (block.lineCount > MIN_CODE_LINES) {
                files.add(FileArtifact.of(block.language, block.code));
            }
        }

        // 没有独立长代码块但是长文档，整体存为文档
        if (files.isEmpty()) {
            // 检测用户是否需要 Word 格式（通过子目标描述或内容关键词判断）
            boolean wantWord = subgoals != null && subgoals.stream()
                    .anyMatch(s -> {
                        String desc = s.getDescription();
                        return desc != null && (desc.toLowerCase().contains("word")
                                || desc.contains("Word文档") || desc.contains("word文档")
                                || desc.contains("docx"));
                    });
            String docType = wantWord ? "docx" : "markdown";
            files.add(FileArtifact.of(docType, answer));
        }

        log.info("ContentClassifier: classified as FILE_OUTPUT, {} file(s)", files.size());
        return OutputDecision.fileOutput(files, answer);
    }

    /**
     * 提取 Markdown 中的代码块。
     *
     * @param text Markdown 文本
     * @return 代码块列表（含语言和行数）
     */
    List<CodeBlock> extractCodeBlocks(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String language = matcher.group(1);
            if (language == null || language.isBlank()) {
                language = "text";
            }
            String code = matcher.group(2);
            int lineCount = code.split("\n").length;
            blocks.add(new CodeBlock(language, code, lineCount));
        }
        return blocks;
    }

    /**
     * 统计表格行数（| 开头的行）。
     */
    int countTableRows(String text) {
        Matcher matcher = TABLE_ROW_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计标题层级数（不同的 # 数量）。
     */
    int countHeaderLevels(String text) {
        Matcher matcher = HEADER_PATTERN.matcher(text);
        int maxLevel = 0;
        while (matcher.find()) {
            int level = matcher.group(1).length();
            if (level > maxLevel) {
                maxLevel = level;
            }
        }
        return maxLevel;
    }

    /**
     * 代码块数据结构。
     */
    record CodeBlock(String language, String code, int lineCount) {}
}