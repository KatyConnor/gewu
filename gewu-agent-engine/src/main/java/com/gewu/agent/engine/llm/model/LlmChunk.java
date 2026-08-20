package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 流式响应分块。
 * <p>每个 chunk 携带增量内容：delta（正式回复）/ reasoning（推理思考）/ toolCallDelta（工具调用增量）/ finishReason。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChunk {

    /** 正式回复增量内容 */
    private String delta;
    /** 推理/思考内容增量（来自 reasoning_content 字段，与正式回复分离） */
    private String reasoning;
    /** 工具调用增量 */
    private ToolCallDelta toolCallDelta;
    /** 结束原因（通常在最后一个 chunk 中） */
    private String finishReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallDelta {
        private String id;
        private String name;
        private String arguments;
    }
}
