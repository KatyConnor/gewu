package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.spi.ToolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolConfigSource} 默认方法语义测试（T1.3 修复验证）。
 * <p>原默认实现以 agentId 与 toolName 相等过滤，语义错误；
 * 修正后：agentId 为空返回全部，非空返回空列表（无绑定信息时视为无绑定）。
 */
@DisplayName("工具配置源默认语义")
class ToolConfigSourceTest {

    private final ToolConfigSource source = new ToolConfigSource() {
        @Override
        public List<ToolConfig> loadTools() {
            return List.of(
                    ToolConfig.builder().toolName("weather").toolType("http").build(),
                    ToolConfig.builder().toolName("search").toolType("http").build());
        }
    };

    @Test
    @DisplayName("agentId 为空时默认返回全部工具配置")
    void nullAgentIdReturnsAll() {
        assertThat(source.loadToolsByAgent(null)).hasSize(2);
    }

    @Test
    @DisplayName("agentId 非空且实现未维护绑定时返回空列表（而非错误的 toolName 匹配）")
    void nonNullAgentIdReturnsEmptyByDefault() {
        assertThat(source.loadToolsByAgent("weather")).isEmpty();
        assertThat(source.loadToolsByAgent("agent-1")).isEmpty();
    }
}
