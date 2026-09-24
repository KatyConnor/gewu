package com.gewu.application.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 会话级聊天运行注册表：追踪每个会话正在进行中的流式任务。
 * <p>核心职责（断连不中断修复）：SSE 客户端断开后任务继续在服务端执行到完成，
 * 前端重新进入会话时通过 {@code active} 状态感知"后台任务仍在跑"并轮询等待；
 * 同时提供同会话并发轮次防护（上一轮未结束前拒绝新流式请求）。
 * <p>状态仅存内存：进程重启即清空（重启丢失的运行任务属于已知限制，
 * 用户消息已在请求入口即时落库，不随进程丢失）。
 */
@Slf4j
@Component
public class ChatRunRegistry {

    /** 终态保留时长：供完成瞬间正在轮询的前端观察到结果，过期惰性清除 */
    private static final long TERMINAL_TTL_MS = 10 * 60 * 1000L;
    /** 运行中任务兜底上限：超过此时长的 RUNNING 记录视为僵残留（进程内无对应订阅），强制可覆盖 */
    private static final long RUNNING_STALE_MS = 3 * 60 * 60 * 1000L;

    public enum Status { RUNNING, DONE, FAILED }

    public record RunMeta(Status status, long startedAt, long finishedAt, String clientId) {
        public boolean active() {
            return status == Status.RUNNING && System.currentTimeMillis() - startedAt < RUNNING_STALE_MS;
        }
    }

    private final Map<String, RunMeta> runs = new ConcurrentHashMap<>();

    /** 活动 run 的实时过程快照供应商（AiChatController 注册 processSummary 引用，重进会话轮询读取） */
    private final Map<String, Supplier<List<Map<String, Object>>>> liveProcesses = new ConcurrentHashMap<>();

    /** 注册活动 run 的实时过程引用（流启动时调用；doFinally 移除） */
    public void putLiveProcess(String sessionId, Supplier<List<Map<String, Object>>> processSupplier) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        liveProcesses.put(sessionId, processSupplier);
    }

    /** 读取活动 run 的实时过程条目（线程安全快照；无活动 run 返回 null，调用方降级） */
    public List<Map<String, Object>> pollLiveProcess(String sessionId) {
        Supplier<List<Map<String, Object>>> supplier = liveProcesses.get(sessionId);
        return supplier == null ? null : supplier.get();
    }

    /** 移除实时过程引用（run 终结时调用，此后由消息 metadata 回放） */
    public void removeLiveProcess(String sessionId) {
        if (sessionId != null) {
            liveProcesses.remove(sessionId);
        }
    }

    /**
     * 尝试注册新的运行轮次。
     *
     * @return false 表示该会话已有活跃运行（调用方应拒绝本次请求）
     */
    public boolean tryRegister(String sessionId, String clientId) {
        evictExpired();
        RunMeta existing = runs.get(sessionId);
        if (existing != null && existing.active()) {
            return false;
        }
        runs.put(sessionId, new RunMeta(Status.RUNNING, System.currentTimeMillis(), 0, clientId));
        return true;
    }

    /** 运行终结（正常完成/带内终止/异常均调 DONE/FAILED，仅用于终止 active 状态） */
    public void finish(String sessionId, Status terminalStatus) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        RunMeta current = runs.get(sessionId);
        if (current == null || !current.active()) {
            return;
        }
        runs.put(sessionId, new RunMeta(terminalStatus, current.startedAt(),
                System.currentTimeMillis(), current.clientId()));
    }

    /** 查询会话当前运行状态；无记录/已过期返回 null */
    public RunMeta get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        RunMeta meta = runs.get(sessionId);
        if (meta == null) {
            return null;
        }
        if (!meta.active() && meta.status() == Status.RUNNING
                && System.currentTimeMillis() - meta.startedAt() > RUNNING_STALE_MS) {
            runs.remove(sessionId);
            return null;
        }
        return meta;
    }

    /** 惰性清理：终态超过 TTL 的记录移除，防止 map 无界增长 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(e -> {
            RunMeta m = e.getValue();
            return m.status() != Status.RUNNING && now - m.finishedAt() > TERMINAL_TTL_MS;
        });
    }
}
