package com.veloflow.engine.trigger;

import com.veloflow.engine.runtime.WorkflowRuntimeListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Webhook 同步返回注册表（53 号 §3.3 respond 配对机制）：respond 节点完成时
 * 经 {@link #onRespond} 通知；触发线程经 {@link #await} 等待 payload（超时回退 202）。
 * <p>进程内实现（单实例部署约定，与调度器 JVM 锁一致）；多副本需换分布式关联（远期）。
 * respond 先于等待注册完成（AUTO 节点在 start 调用线程内同步完成）时落
 * {@link #completed}，由 {@link #pollCompleted} 兜底取走。
 */
@Component
public class WebhookResponseRegistry implements WorkflowRuntimeListener {

    private final Map<String, CompletableFuture<String>> waiters = new ConcurrentHashMap<>();
    private final Map<String, String> completed = new ConcurrentHashMap<>();

    /** 触发线程在 startByTrigger 返回后注册等待 */
    public void register(String instanceId) {
        waiters.put(instanceId, new CompletableFuture<>());
    }

    /** 已先完成的 payload（respond 在 start 调用线程内同步完成的场景） */
    public String pollCompleted(String instanceId) {
        return completed.remove(instanceId);
    }

    /** 等待 respond payload；超时抛 TimeoutException */
    public String await(String instanceId, long timeoutMs) throws TimeoutException {
        CompletableFuture<String> future = waiters.get(instanceId);
        if (future == null) {
            throw new IllegalStateException("未注册等待: " + instanceId);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("等待 respond 失败", e);
        }
    }

    /** 触发线程 finally 清理（防泄漏） */
    public void cleanup(String instanceId) {
        waiters.remove(instanceId);
        completed.remove(instanceId);
    }

    @Override
    public void onRespond(String instanceId, String payloadJson) {
        CompletableFuture<String> waiter = waiters.remove(instanceId);
        if (waiter != null) {
            waiter.complete(payloadJson);
        } else {
            completed.put(instanceId, payloadJson);
        }
    }
}
