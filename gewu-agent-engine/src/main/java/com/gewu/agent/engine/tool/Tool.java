package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.llm.model.ToolDefinition;

/**
 * 工具接口 - 代码级注册的工具实现此接口。
 * <p>使用 {@link ToolProvider} 注解标记实现类，框架启动时自动扫描注册到 {@link ToolRegistry}。
 * 工具执行由 {@link ToolExecutor} 统一调度，先经过安全管线再调用 {@link #invoke}。
 *
 * @since 1.0.0
 */
public interface Tool {

    /** 工具定义（名称 / 描述 / JSON Schema 参数） */
    ToolDefinition getDefinition();

    /**
     * 执行工具。
     *
     * @param arguments 参数（JSON 字符串）
     * @param context   执行上下文
     * @return 执行结果
     */
    ToolResult invoke(String arguments, ToolContext context);
}
