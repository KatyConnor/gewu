package com.gewu.agent.engine.memory;

import com.gewu.agent.engine.llm.model.Message;

import java.util.List;

/**
 * {@link MemoryRouter} 的 NoOp 默认实现 - 不注入记忆。
 *
 * @since 1.0.0
 */
public class NoOpMemoryRouter implements MemoryRouter {

    @Override
    public List<Message> inject(String domain, List<Message> messages, String taskInput) {
        return messages;
    }
}