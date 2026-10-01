package com.gewu.desktop.adapter;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryRouter;
import com.gewu.agent.engine.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面记忆路由（D2-10）：检索 topK 片段 → 以 system 消息前置注入。
 * 引擎自动装配的 NoOpMemoryRouter 在本 Bean 存在时让位（@ConditionalOnMissingBean）。
 * 注入预算：单次最多 3 条、每条 500 字（v1 关键词检索的保守策略）。
 *
 * @since 1.0.0
 */
@Component
public class DesktopMemoryRouter implements MemoryRouter {

    private static final int TOP_K = 3;
    private static final int FRAGMENT_CAP = 500;

    private final MemoryStore memoryStore;

    public DesktopMemoryRouter(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public List<Message> inject(String domain, List<Message> messages, String taskInput) {
        if (taskInput == null || taskInput.isBlank()) {
            return messages;
        }
        try {
            List<MemoryFragment> fragments = memoryStore.retrieve(domain, taskInput, TOP_K);
            if (fragments.isEmpty()) {
                return messages;
            }
            StringBuilder sb = new StringBuilder("以下是与此任务相关的历史经验记忆（供参考，非当前对话内容）：\n");
            for (MemoryFragment f : fragments) {
                String c = f.getContent();
                sb.append("- ").append(c, 0, Math.min(c.length(), FRAGMENT_CAP)).append("\n");
            }
            List<Message> injected = new ArrayList<>();
            Message memory = new Message();
            memory.setRole("system");
            memory.setContent(sb.toString());
            injected.add(memory);
            injected.addAll(messages);
            return injected;
        } catch (Exception e) {
            // 记忆检索失败不阻塞对话
            return messages;
        }
    }
}
