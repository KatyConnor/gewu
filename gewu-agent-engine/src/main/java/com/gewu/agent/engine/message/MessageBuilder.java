package com.gewu.agent.engine.message;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.AgentSpec;

import java.util.List;

/**
 * 消息构建器 SPI - 构建 LLM 请求的消息列表（system prompt + 历史 + 用户消息）。
 * <p>使用方可实现此接口定制消息构建逻辑（如注入技能 / 记忆 / RAG 上下文）。
 * 框架提供 {@link DefaultMessageBuilder} 默认实现。
 *
 * @since 1.0.0
 */
public interface MessageBuilder {

    /**
     * 构建消息列表。
     *
     * @param agent         Agent 配置（可为 null，直接对话模式）
     * @param userMessage   用户消息
     * @param history       历史消息列表（可为 null）
     * @param agentMode     Agent 模式
     * @param thinkingStyle 思维模式
     * @return 消息列表
     */
    List<Message> buildMessages(AgentSpec agent, String userMessage, List<Message> history,
                                String agentMode, String thinkingStyle);
}
