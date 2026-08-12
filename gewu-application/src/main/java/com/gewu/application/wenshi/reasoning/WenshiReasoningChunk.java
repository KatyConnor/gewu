package com.gewu.application.wenshi.reasoning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wenshi 推理流式响应块。
 * <p>
 * 用于封装流式推理过程中的增量输出内容，支持文本内容、工具调用、工具结果及错误信息。
 * 通过 {@code type} 字段区分不同类型的响应块。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WenshiReasoningChunk {

    /**
     * 块类型。
     * <p>
     * 取值：content / thinking / status / tool_call / tool_result / error /
     * web_search_start / web_search_result / web_verifying / web_verdict / file。
     */
    private String type;

    /** 文本内容（当 type 为 content/status 时有效） */
    private String content;

    /** 推理/思考内容（当 type 为 thinking 时有效） */
    private String reasoning;

    /** 工具调用信息（当 type 为 tool_call 时有效） */
    private ToolCallInfo toolCall;

    /** 工具执行结果（当 type 为 tool_result 时有效） */
    private ToolResultInfo toolResult;

    /** 网络搜索综合信息（当 type 为 web_search_result 时有效） */
    private WebSearchInfo webSearch;

    /** 正确性判断结论信息（当 type 为 web_verdict 时有效） */
    private VerifyInfo verify;

    /** 文件卡片信息（当 type 为 file 时有效） */
    private FileInfo file;

    /** 错误信息（当 type 为 error 时有效） */
    private String errorMessage;

    /**
     * 工具调用信息。
     * <p>
     * 记录 LLM 发起的工具调用请求详情，包含工具名称和参数。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        /** 调用唯一标识，用于关联工具结果 */
        private String id;

        /** 工具名称 */
        private String name;

        /** 工具调用参数（JSON 格式字符串） */
        private String arguments;
    }

    /**
     * 工具执行结果信息。
     * <p>
     * 记录工具执行后的返回结果，通过 toolCallId 与调用请求关联。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResultInfo {
        /** 关联的工具调用 ID */
        private String toolCallId;

        /** 工具名称 */
        private String name;

        /** 工具执行结果内容 */
        private String result;
    }

    /**
     * 网络搜索综合信息。
     * <p>
     * 封装一次搜索的查询、全部结果条目及采纳/丢弃统计，
     * 与 {@code web_search_result} 事件配合使用。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebSearchInfo {
        /** 搜索查询语句 */
        private String query;

        /** 搜索结果条目列表 */
        private List<SearchItem> results;

        /** 采纳结果数 */
        private int adoptedCount;

        /** 丢弃结果数 */
        private int discardedCount;
    }

    /**
     * 网络搜索结果条目。
     * <p>
     * 单条搜索结果的精简表示，包含原始信息和正确性判断后的状态标记。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItem {
        /** 结果 URL */
        private String url;

        /** 结果标题 */
        private String title;

        /** 摘要文本 */
        private String snippet;

        /** 来源域名 */
        private String source;

        /** 是否采纳 */
        private boolean adopted;

        /** 是否丢弃 */
        private boolean discarded;

        /** 丢弃原因 */
        private String discardReason;

        /** 置信度评分 0.0-1.0 */
        private double confidence;
    }

    /**
     * 正确性判断结论信息。
     * <p>
     * 与 {@code web_verdict} 事件配合，用于向前端推送最终判断结论。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyInfo {
        /** 验证方法（RULE / CONSENSUS / SCORE / LLM / PASSTHROUGH） */
        private String method;

        /** 验证的查询语句 */
        private String query;

        /** 搜索结果总数 */
        private int totalResults;

        /** 采纳结果数 */
        private int adoptedCount;

        /** 丢弃结果数 */
        private int discardedCount;
    }

    /**
     * 文件卡片信息。
     * <p>
     * 与 {@code file} 事件配合，向前端推送 AI 生成的文件元信息（文件名、下载 URL、预览内容）。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileInfo {
        /** 文件名 */
        private String fileName;

        /** 文件类型（code / markdown / json / yaml / csv / docx） */
        private String fileType;

        /** MIME 类型 */
        private String mimeType;

        /** 文件大小（字节） */
        private long fileSize;

        /** 下载 URL（MinIO 预签名） */
        private String downloadUrl;

        /** 预览内容（前 500 字符） */
        private String previewContent;

        /** 来源标识（ai） */
        private String source;
    }
}
