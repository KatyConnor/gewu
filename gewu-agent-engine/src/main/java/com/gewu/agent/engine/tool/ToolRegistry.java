package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心 - 管理代码级注册的 {@link Tool}。
 * <p>启动时收集所有 {@link ToolProvider} 标注的 Tool Bean，按工具名索引。
 * 代码工具优先于配置工具（{@link com.gewu.agent.engine.spi.ToolConfig}）被执行。
 *
 * @since 1.0.0
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolBeans) {
        this.tools = toolBeans == null ? Map.of()
                : toolBeans.stream()
                .collect(Collectors.toUnmodifiableMap(t -> t.getDefinition().getName(), t -> t, (a, b) -> a));
        log.info("ToolRegistry 已注册 {} 个代码工具: {}", tools.size(), tools.keySet());
    }

    /** 按名称解析工具，不存在返回 null */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /** 列出所有代码工具定义 */
    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream().map(Tool::getDefinition).toList();
    }

    /** 是否存在指定工具 */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
