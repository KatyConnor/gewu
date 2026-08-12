package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    private String type;
    private String content;
    /** 推理/思考内容（thinking 事件） */
    private String reasoning;
    private ToolCallInfo toolCall;
    private ToolResultInfo toolResult;
    /** 网络搜索综合信息（web_search_result 事件） */
    private WebSearchEventInfo webSearch;
    /** 正确性判断结论（web_verdict 事件） */
    private VerifyEventInfo verify;
    /** 文件卡片信息（file 事件） */
    private FileEventInfo file;
    private String errorMessage;

    /**
     * 网络搜索事件信息（与 WenshiReasoningChunk.WebSearchInfo 同构）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebSearchEventInfo {
        private String query;
        private List<SearchItemInfo> results;
        private int adoptedCount;
        private int discardedCount;
    }

    /**
     * 网络搜索结果条目信息（与 WenshiReasoningChunk.SearchItem 同构）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItemInfo {
        private String url;
        private String title;
        private String snippet;
        private String source;
        private boolean adopted;
        private boolean discarded;
        private String discardReason;
        private double confidence;
    }

    /**
     * 正确性判断结论信息（与 WenshiReasoningChunk.VerifyInfo 同构）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyEventInfo {
        private String method;
        private String query;
        private int totalResults;
        private int adoptedCount;
        private int discardedCount;
    }

    /**
     * 文件卡片信息（与 WenshiReasoningChunk.FileInfo 同构）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEventInfo {
        private String fileName;
        private String fileType;
        private String mimeType;
        private long fileSize;
        private String downloadUrl;
        private String previewContent;
        private String source;
    }
}
