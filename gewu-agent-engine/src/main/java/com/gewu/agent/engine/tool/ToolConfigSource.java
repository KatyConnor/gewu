package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.spi.ToolConfig;

import java.util.List;

/**
 * 工具配置源 SPI - 从外部来源（数据库 / 配置文件 / 配置中心）加载工具配置。
 * <p>使用方实现此接口，提供 {@code http} / {@code mcp} / {@code code_execute} 类型的工具配置。
 * 框架提供 {@code NoOpToolConfigSource} 默认实现（返回空），此时仅代码注册的工具可用。
 *
 * @since 1.0.0
 */
public interface ToolConfigSource {

    /** 加载全部工具配置 */
    List<ToolConfig> loadTools();

    /**
     * 加载指定 Agent 绑定的工具配置。
     * <p>{@link ToolConfig} 本身不携带 Agent 绑定信息，默认实现无法按 Agent 过滤：
     * agentId 为空时返回全部，非空时返回空列表（视为无绑定）。
     * 使用方若维护 Agent-工具关联（如 agent_tool 关联表），应重写本方法。
     */
    default List<ToolConfig> loadToolsByAgent(String agentId) {
        if (agentId == null) {
            return loadTools();
        }
        return List.of();
    }
}
