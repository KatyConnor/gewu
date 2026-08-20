package com.gewu.agent.engine.memory;

import com.gewu.agent.engine.llm.model.Message;

import java.util.List;

/**
 * 记忆路由 SPI - 判断任务需要哪些记忆并注入消息。
 * <p>规则优先 + LLM 兜底：根据任务特征判断检索哪些记忆域 / 类型，
 * 检索后去重 / 截断（token 预算）/ 注入 system prompt。
 *
 * @since 1.0.0
 */
public interface MemoryRouter {

    /**
     * 为消息列表注入记忆上下文。
     *
     * @param domain    记忆域
     * @param messages  原始消息列表
     * @param taskInput 任务输入（用于检索）
     * @return 注入记忆后的消息列表
     */
    List<Message> inject(String domain, List<Message> messages, String taskInput);
}