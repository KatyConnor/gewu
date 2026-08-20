package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 同步响应模型。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    /** 文本回复内容 */
    private String content;
    /** LLM 请求执行的工具调用列表（为空表示无需工具，回复即最终答案） */
    private List<ToolCall> toolCalls;
    /** token 用量统计 */
    private Usage usage;
    /** 结束原因：stop / length / tool_calls / content_filter */
    private String finishReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
