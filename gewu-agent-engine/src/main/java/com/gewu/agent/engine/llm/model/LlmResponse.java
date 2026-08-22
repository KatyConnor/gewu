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
    /**
     * 思考内容回退标记：content 为空且无工具调用时以 reasoning_content 兜底返回，
     * 标记为 true 供调用方感知（可能是 token 耗尽未产出正式回复，需要提示用户）。
     */
    @Builder.Default
    private boolean reasoningFallback = false;

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
