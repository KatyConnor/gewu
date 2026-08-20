package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.SessionContextService;

import java.util.List;

/**
 * {@link SessionContextService} 的 NoOp 默认实现 - 无历史上下文。
 *
 * @since 1.0.0
 */
public class NoOpSessionContextService implements SessionContextService {

    @Override
    public List<Message> buildContextMessages(String sessionId, int limit) {
        return List.of();
    }

    @Override
    public void appendInteraction(String sessionId, String userId, String userMessage, String assistantContent) {
        // NoOp: 不持久化
    }
}
