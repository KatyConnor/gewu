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
        return compose(agent, agentMode, thinkingStyle, null, null);
    }

    /**
     * 组装 system prompt（含入口调度指令段）。
     * <p>注入位置在 Agent 人设之后、模式指令之前：用户/项目级规范应压过"模式措辞"，
     * 又不覆盖智能体人设本体（设计文档 56 §5.3）。指令为空时与旧签名完全等价。
     *
     * @param entryDirectives 入口调度指令内容（AGENTS.md/GEWU.md 裁决产物，null/空=不注入）
     * @param entrySource     来源标签（如 project:AGENTS.md@1.3.0）
     */
    public String compose(AgentSpec agent, String agentMode, String thinkingStyle,
                          String entryDirectives, String entrySource) {
        StringBuilder sb = new StringBuilder();

        if (agent != null && agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            sb.append(agent.getSystemPrompt());
        }

        if (entryDirectives != null && !entryDirectives.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("# 调度入口指令");
            if (entrySource != null && !entrySource.isBlank()) {
                sb.append("（来源：").append(entrySource).append("）");
            }
            sb.append("\n\n").append(entryDirectives);
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
