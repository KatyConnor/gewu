package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.spi.ToolConfig;

import java.util.List;

/**
 * {@link ToolConfigSource} 的 NoOp 默认实现 - 不提供外部工具配置。
 *
 * @since 1.0.0
 */
public class NoOpToolConfigSource implements ToolConfigSource {

    @Override
    public List<ToolConfig> loadTools() {
        return List.of();
    }
}
