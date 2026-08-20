package com.gewu.agent.engine.spi;

import com.gewu.agent.engine.llm.model.Message;

import java.util.List;

/**
 * 会话上下文服务 SPI - 会话历史消息的构建与持久化。
 * <p>使用方实现此接口，提供会话历史上下文检索与交互记录保存。
 * 框架提供 {@code NoOpSessionContextService} 默认实现（返回空历史）。
 *
 * @since 1.0.0
 */
public interface SessionContextService {

    /**
     * 构建会话历史上下文消息列表。
     *
     * @param sessionId 会话标识
     * @param limit     最大历史条数
     * @return 历史消息列表（不含当前用户消息）
     */
    List<Message> buildContextMessages(String sessionId, int limit);

    /**
     * 追加一次对话交互记录（用户消息 + 助手回复）。
     *
     * @param sessionId        会话标识
     * @param userId           用户标识
     * @param userMessage      用户消息
     * @param assistantContent 助手回复内容
     */
    default void appendInteraction(String sessionId, String userId,
                                   String userMessage, String assistantContent) {
    }
}
