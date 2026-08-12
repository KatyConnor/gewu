package com.gewu.application.wenshi.output;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件信息 - 保存到存储后的文件元信息，用于流式事件和前端渲染。
 * <p>
 * 由 {@link FileOutputService#generateFiles} 产出，
 * 被转换为 {@code WenshiReasoningChunk.FileInfo} / {@code ChatStreamEvent.FileEventInfo}
 * 发送到前端，渲染为文件卡片。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {

    /** 文件名（如 HelloWorld.java、report.md） */
    private String fileName;

    /** 文件类型（code / markdown / json / yaml / csv / docx） */
    private String fileType;

    /** MIME 类型 */
    private String mimeType;

    /** 文件大小（字节） */
    private long fileSize;

    /** 下载 URL（MinIO 预签名，7 天有效） */
    private String downloadUrl;

    /** 预览内容（前 500 字符） */
    private String previewContent;

    /** 来源标识（ai） */
    private String source;
}