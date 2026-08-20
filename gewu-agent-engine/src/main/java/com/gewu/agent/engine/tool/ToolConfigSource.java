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
     * <p>默认实现从 {@link #loadTools()} 过滤；使用方可重写为更高效的按 Agent 查询。
     */
    default List<ToolConfig> loadToolsByAgent(String agentId) {
        return loadTools().stream()
                .filter(t -> agentId == null || agentId.equals(t.getToolName()))
                .toList();
    }
}
