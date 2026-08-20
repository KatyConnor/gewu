package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;

/**
 * 安全检查接口 - 工具执行前的可插拔安全校验。
 * <p>多个 {@link SecurityCheck} 组成 {@link SecurityChain}，按序执行。
 *
 * @since 1.0.0
 */
public interface SecurityCheck {

    /**
     * 执行安全检查，校验失败抛出 {@link com.gewu.agent.engine.AgentEngineException}。
     *
     * @param toolName  工具名
     * @param arguments 参数（JSON 字符串）
     * @param context   执行上下文
     * @param config    工具配置（可能为 null，仅代码工具时）
     */
    void check(String toolName, String arguments, ToolContext context, ToolConfig config);
}
