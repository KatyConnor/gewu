package com.gewu.application.wenshi.output;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件产物数据结构 - 表示一个待保存的 AI 生成文件。
 * <p>
 * 由 {@link ContentClassifier} 从推理结果中拆分产出，
 * 交由 {@link FileOutputService} 存储到 MinIO。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileArtifact {

    /** 文件类型（code / markdown / json / yaml / csv / docx） */
    private String fileType;

    /** 编程语言标识（如 java / python / typescript / sql，仅 code 类型有效） */
    private String language;

    /** 文件内容（Markdown 类型为原始 Markdown 文本；docx 类型为 Markdown 待转换） */
    private String content;

    /** 建议的文件名（如未指定，FileOutputService 会自动生成） */
    private String suggestedFileName;

    /**
     * 快速构建代码文件产物。
     *
     * @param language 编程语言
     * @param code     代码内容
     * @return 文件产物实例
     */
    public static FileArtifact of(String language, String code) {
        String fileType = inferFileType(language);
        return FileArtifact.builder()
                .fileType(fileType)
                .language(language)
                .content(code)
                .build();
    }

    /**
     * 根据语言标识推断文件类型。
     */
    private static String inferFileType(String language) {
        if (language == null) {
            return "markdown";
        }
        return switch (language.toLowerCase()) {
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "csv" -> "csv";
            case "markdown", "md" -> "markdown";
            case "word", "docx" -> "docx";
            default -> "code";
        };
    }
}