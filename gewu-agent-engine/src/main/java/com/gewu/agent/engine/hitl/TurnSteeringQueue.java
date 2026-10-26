package com.gewu.agent.engine.hitl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 会话执行中转向队列（用户插话补充指令）。
 * <p>流式任务执行期间，用户新发的指令由接口层持久化（用户消息照常落库）
 * 后入队本组件；ReAct 循环在每个轮次边界排水注入——以 user 角色消息并入
 * 当前上下文，与既有工具结果、原指令一起参与下一轮推理（queue-and-inject，
 * 不中断不重开）。仅顶层任务（agentDepth=0）排水，子代理不消费父会话指令。
 * <p>上限：每会话积压 ≤{@value #MAX_PER_SESSION} 条、单条 ≤{@value #MAX_CHARS} 字符，
 * 超限拒收（拒绝语义由接口层回传前端）。
 *
 * @since 1.0.0
 */
@Component
public class TurnSteeringQueue {

    private static final int MAX_PER_SESSION = 5;
    private static final int MAX_CHARS = 2000;

    private final java.util.concurrent.ConcurrentHashMap<String, Queue<String>> queues =
            new ConcurrentHashMap<>();

    /**
     * 入队一条补充指令。
     *
     * @return false=会话积压已满或内容超限/为空（调用方应回传前端降级提示）
     */
    public boolean offer(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()
                || content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CHARS) {
            trimmed = trimmed.substring(0, MAX_CHARS);
        }
        Queue<String> queue = queues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());
        while (queue.size() >= MAX_PER_SESSION) {
            queue.poll(); // 挤掉最旧，保留最新指令
        }
        return queue.offer(trimmed);
    }

    /** 排水：取出并清空该会话全部积压指令（按提交顺序），无积压返回空列表 */
    public List<String> drain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Queue<String> queue = queues.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<String> drained = new ArrayList<>();
        String item;
        while ((item = queue.poll()) != null) {
            drained.add(item);
        }
        return drained;
    }

    /** 当前积压条数（观测用） */
    public int pending(String sessionId) {
        Queue<String> queue = sessionId == null ? null : queues.get(sessionId);
        return queue == null ? 0 : queue.size();
    }
}
