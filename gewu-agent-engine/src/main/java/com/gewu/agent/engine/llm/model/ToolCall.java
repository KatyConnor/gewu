package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 返回的工具调用请求。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** 调用 ID（由 LLM 分配，用于关联工具结果） */
    private String id;
    /** 工具名称 */
    private String name;
    /** 参数（JSON 字符串） */
    private String arguments;
}
