package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 工具定义（Function Calling）。
 * <p>parameters 为 JSON Schema 字符串，描述工具入参结构。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具名称 */
    private String name;
    /** 工具描述 */
    private String description;
    /** JSON Schema 格式的参数定义 */
    private String parameters;
}
