package com.gewu.agent.engine.message;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.AgentSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MessageBuilder} 默认实现 - 组装 system prompt + 历史 + 用户消息。
 *
 * @since 1.0.0
 */
public class DefaultMessageBuilder implements MessageBuilder {

    private final SystemPromptComposer composer;

    public DefaultMessageBuilder(SystemPromptComposer composer) {
        this.composer = composer;
    }

    @Override
    public List<Message> buildMessages(AgentSpec agent, String userMessage, List<Message> history,
                                       String agentMode, String thinkingStyle) {
        List<Message> messages = new ArrayList<>();

        String systemPrompt = composer.compose(agent, agentMode, thinkingStyle);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Message.builder().role("system").content(systemPrompt).build());
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
