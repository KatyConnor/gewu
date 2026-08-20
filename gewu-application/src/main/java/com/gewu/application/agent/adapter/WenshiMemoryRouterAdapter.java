package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.memory.MemoryRouter;
import com.gewu.application.wenshi.knowledge.MemoryInjector;
import com.gewu.application.wenshi.knowledge.WorkingMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MemoryRouter SPI 适配器 - 桥接 Wenshi 记忆路由与注入到 Agent 引擎。
 * <p>使用 Wenshi {@link com.gewu.application.wenshi.knowledge.MemoryRouter} 检索
 * 相关记忆，经 {@link MemoryInjector} 生成记忆摘要后注入到消息列表的 system 消息中。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiMemoryRouterAdapter implements MemoryRouter {

    private final com.gewu.application.wenshi.knowledge.MemoryRouter wenshiMemoryRouter;
    private final MemoryInjector memoryInjector;
    private final WorkingMemoryService workingMemoryService;

    private static final String DEFAULT_TENANT = "default";
    private static final String SYSTEM_USER = "system";

    /**
     * 为消息列表注入记忆上下文。
     * <p>检索与 taskInput 相关的记忆，生成摘要后追加到 system 消息。
     * 若无 system 消息则在列表头部插入一条。
     */
    @Override
    public List<Message> inject(String domain, List<Message> messages, String taskInput) {
        if (taskInput == null || taskInput.isBlank() || messages == null || messages.isEmpty()) {
            return messages;
        }
        try {
            String tenantId = domain != null ? domain : DEFAULT_TENANT;
            var routing = wenshiMemoryRouter.route(tenantId, SYSTEM_USER, "DEFAULT", taskInput);
            var plan = memoryInjector.prepareInjection(routing);

            // 附加工作记忆（会话内中间状态）
            var workingMemories = workingMemoryService.getAll(tenantId);
            StringBuilder summary = new StringBuilder();
            if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
                summary.append(plan.getSummary());
            }
            if (!workingMemories.isEmpty()) {
                summary.append("\n## 会话工作记忆\n");
                workingMemories.forEach((k, v) ->
                        summary.append("- ").append(k).append(": ").append(v).append("\n"));
            }

            if (summary.length() == 0) {
                return messages;
            }

            List<Message> result = new ArrayList<>(messages);
            boolean injected = false;
            for (int i = 0; i < result.size(); i++) {
                if ("system".equals(result.get(i).getRole())) {
                    Message existing = result.get(i);
                    result.set(i, Message.builder()
                            .role("system")
                            .content(existing.getContent() + "\n\n## 相关记忆\n" + summary)
                            .build());
                    injected = true;
                    break;
                }
            }
            if (!injected) {
                result.add(0, Message.builder()
                        .role("system")
                        .content("## 相关记忆\n" + summary)
                        .build());
            }
            log.debug("WenshiMemoryRouterAdapter.inject: domain={}, taskInput={}, tokenEstimate={}",
                    tenantId, taskInput, plan.getTokenEstimate());
            return result;
        } catch (Exception e) {
            log.warn("WenshiMemoryRouterAdapter.inject failed, returning original messages: {}", e.getMessage());
            return messages;
        }
    }
}
