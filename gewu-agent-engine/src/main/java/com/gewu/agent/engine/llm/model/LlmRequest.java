package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 请求模型。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /** 模型名称，如 qwen-plus / deepseek-chat */
    private String model;
    /** 对话消息列表 */
    private List<Message> messages;
    /** 可用工具定义列表 */
    private List<ToolDefinition> tools;
    /** 采样温度 */
    private Double temperature;
    /** 最大生成 token 数 */
    private Integer maxTokens;
    /** 是否流式响应 */
    private Boolean stream;
}
