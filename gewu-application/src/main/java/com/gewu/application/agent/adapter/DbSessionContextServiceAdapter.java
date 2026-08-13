package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.SessionContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link SessionContextService} 业务适配 - 桥接框架与现有业务侧 SessionContextService。
 * <p>业务侧 {@code buildContextMessages} 返回 infrastructure 层 Message，
 * 此处转换为引擎层 {@link Message}。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbSessionContextServiceAdapter implements SessionContextService {

    private final com.gewu.application.session.SessionContextService delegate;

    @Override
    public List<Message> buildContextMessages(String sessionId, int limit) {
        return delegate.buildContextMessages(sessionId, limit).stream()
                .map(m -> Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .toolCallId(m.getToolCallId())
                        .name(m.getName())
                        .build())
                .toList();
    }

    @Override
    public void appendInteraction(String sessionId, String userId, String userMessage, String assistantContent) {
        delegate.appendChatInteraction(sessionId, userId, userMessage, assistantContent);
    }
}