package com.gewu.agent.engine.memory;

import java.util.List;

/**
 * {@link MemoryStore} 的 NoOp 默认实现 - 无记忆能力。
 *
 * @since 1.0.0
 */
public class NoOpMemoryStore implements MemoryStore {

    @Override
    public void store(MemoryFragment fragment) {
        // NoOp
    }

    @Override
    public List<MemoryFragment> retrieve(String domain, String query, int topK) {
        return List.of();
    }
}