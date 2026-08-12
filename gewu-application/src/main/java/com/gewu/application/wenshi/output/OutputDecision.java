package com.gewu.application.wenshi.output;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内容分类判定结果。
 * <p>
 * 由 {@link ContentClassifier} 产出，决定推理结果是直接输出到对话框还是保存为文件。
 * <ul>
 *   <li>DIRECT：简单问答，直接输出到对话框</li>
 *   <li>FILE_OUTPUT：复杂内容，保存为文件 + 对话框输出摘要</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputDecision {

    /** 判定类型 */
    private DecisionType type;

    /** 直接输出到对话框的内容（DIRECT 模式为完整 answer，FILE_OUTPUT 模式为摘要） */
    private String displayContent;

    /** 待保存的文件列表（仅 FILE_OUTPUT 模式有效） */
    private List<FileArtifact> files;

    /** 完整原始内容（FILE_OUTPUT 模式用于生成摘要） */
    private String fullContent;

    /**
     * 判定类型枚举。
     */
    public enum DecisionType {
        /** 直接输出到对话框 */
        DIRECT,
        /** 保存为文件，对话框输出摘要 */
        FILE_OUTPUT
    }

    /**
     * 创建直接输出判定结果。
     *
     * @param answer 完整回答文本
     * @return DIRECT 判定结果
     */
    public static OutputDecision direct(String answer) {
        return OutputDecision.builder()
                .type(DecisionType.DIRECT)
                .displayContent(answer)
                .fullContent(answer)
                .build();
    }

    /**
     * 创建文件输出判定结果。
     *
     * @param files     待保存的文件列表
     * @param fullContent 完整原始内容（用于生成摘要）
     * @return FILE_OUTPUT 判定结果
     */
    public static OutputDecision fileOutput(List<FileArtifact> files, String fullContent) {
        return OutputDecision.builder()
                .type(DecisionType.FILE_OUTPUT)
                .files(files)
                .fullContent(fullContent)
                .displayContent(null) // 摘要由 FileOutputService 后续生成
                .build();
    }
}