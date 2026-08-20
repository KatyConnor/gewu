package com.gewu.agent.engine.message;

import com.gewu.agent.engine.spi.AgentSpec;

/**
 * 系统提示词组装器 - 将 Agent 配置、模式指令、思维指令组装为完整 system prompt。
 *
 * @since 1.0.0
 */
public class SystemPromptComposer {

    /**
     * 组装 system prompt。
     *
     * @param agent        Agent 配置（可为 null）
     * @param agentMode    Agent 模式
     * @param thinkingStyle 思维模式
     * @return 组装后的 system prompt，为空返回 null
     */
    public String compose(AgentSpec agent, String agentMode, String thinkingStyle) {
        StringBuilder sb = new StringBuilder();

        if (agent != null && agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            sb.append(agent.getSystemPrompt());
        }

        String modeDirective = PromptDirective.buildAgentModeDirective(agentMode);
        if (modeDirective != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(modeDirective);
        }

        String thinkingDirective = PromptDirective.buildThinkingStyleDirective(thinkingStyle);
        if (thinkingDirective != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(thinkingDirective);
        }

        return sb.length() > 0 ? sb.toString() : null;
    }
}
