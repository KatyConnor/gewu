package com.gewu.agent.engine.message;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.AgentSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link MessageBuilder} 默认实现 - 组装 system prompt + 历史 + 用户消息。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-08-20
 */
public class DefaultMessageBuilder implements MessageBuilder {

    private final SystemPromptComposer composer;
    /** 段贡献者（AE-P2-3 收口）：按 order 排序，非空段以 "# {标题}\n{内容}" 追加到 system 尾部 */
    private final List<PromptSectionContributor> contributors;

    public DefaultMessageBuilder(SystemPromptComposer composer) {
        this(composer, List.of());
    }

    public DefaultMessageBuilder(SystemPromptComposer composer, List<PromptSectionContributor> contributors) {
        this.composer = composer;
        this.contributors = contributors != null
                ? contributors.stream()
                        .sorted(Comparator.comparingInt(PromptSectionContributor::order))
                        .toList()
                : List.of();
    }

    @Override
    public List<Message> buildMessages(AgentSpec agent, String userMessage, List<Message> history,
                                       String agentMode, String thinkingStyle) {
        return buildMessages(agent, userMessage, history, agentMode, thinkingStyle, null, null);
    }

    @Override
    public List<Message> buildMessages(AgentSpec agent, String userMessage, List<Message> history,
                                       String agentMode, String thinkingStyle,
                                       String entryDirectives, String entrySource) {
        List<Message> messages = new ArrayList<>();

        // AE-P2-3 收口：基础段（composer）+ 扩展段贡献者（技能段等）统一组装——
        // 贡献者返回含 "# 标题" 行的完整段，调用方仅以空行分隔（迁移前后逐字节一致）
        StringBuilder systemBuilder = new StringBuilder();
        String systemPrompt = composer.compose(agent, agentMode, thinkingStyle, entryDirectives, entrySource);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            systemBuilder.append(systemPrompt);
        }
        for (PromptSectionContributor contributor : contributors) {
            String section = contributor.renderSection(agent, userMessage);
            if (section != null && !section.isBlank()) {
                if (systemBuilder.length() > 0) {
                    systemBuilder.append("\n\n");
                }
                systemBuilder.append(section);
            }
        }
        if (systemBuilder.length() > 0) {
            messages.add(Message.builder().role("system").content(systemBuilder.toString()).build());
        }

        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        if (userMessage != null && !userMessage.isEmpty()) {
            messages.add(Message.builder().role("user").content(userMessage).build());
        }

        return messages;
    }
}
