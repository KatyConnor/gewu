package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.core.event.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 生命周期管理器 - 创建、监控、死锁检测、超时终止、回收。
 * <p>防失控核心组件：
 * <ul>
 *   <li>心跳检测：30s 间隔检查活跃 Agent 是否有心跳</li>
 *   <li>死锁检测：等待环检测算法（检测 Agent 间循环等待）</li>
 *   <li>超时处理：全局超时 + 强制终止</li>
 *   <li>资源回收：记录执行指标 + 释放资源</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class AgentLifecycleManager {

    /** 心跳超时阈值（毫秒） */
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000;
    /** 全局执行超时（毫秒） */
    private static final long GLOBAL_TIMEOUT_MS = 300_000;
    /** 最大等待链深度（防环检测） */
    private static final int MAX_WAIT_DEPTH = 8;

    /** 活跃 Agent 实例注册表 (instanceId -> AgentInstance) */
    private final Map<String, AgentInstance> activeAgents = new ConcurrentHashMap<>();

    /**
     * 创建 Agent 实例。
     */
    public AgentInstance spawn(String roleCode, String executionId, String collaborationId) {
        String instanceId = UUID.randomUUID().toString();
        AgentInstance instance = new AgentInstance(
                instanceId, roleCode, executionId, collaborationId,
                System.currentTimeMillis(), System.currentTimeMillis(),
                AgentInstance.Status.RUNNING, null, null);
        activeAgents.put(instanceId, instance);
        log.info("AgentLifecycleManager.spawn: instanceId={}, role={}, execution={}",
                instanceId, roleCode, executionId);
        return instance;
    }

    /**
     * 更新心跳。
     */
    public void heartbeat(String instanceId) {
        AgentInstance agent = activeAgents.get(instanceId);
        if (agent != null) {
            agent.setLastHeartbeat(System.currentTimeMillis());
        }
    }

    /**
     * 标记 Agent 等待另一个 Agent（用于死锁检测）。
     */
    public void markWaiting(String instanceId, String waitingForId) {
        AgentInstance agent = activeAgents.get(instanceId);
        if (agent != null) {
            agent.setWaitingForId(waitingForId);
        }
    }

    /**
     * 清除等待标记。
     */
    public void clearWaiting(String instanceId) {
        AgentInstance agent = activeAgents.get(instanceId);
        if (agent != null) {
            agent.setWaitingForId(null);
        }
    }

    /**
     * 监控活跃 Agent 状态：检测心跳超时、死锁、全局超时。
     *
     * @param sink 流式事件 Sink（用于推送告警事件）
     * @return 检测到问题的 Agent 列表
     */
    public java.util.List<AgentInstance> monitor(FluxSink<AgentEvent> sink) {
        java.util.List<AgentInstance> problematic = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();

        for (AgentInstance agent : activeAgents.values()) {
            // 1. 心跳超时检测
            if (now - agent.getLastHeartbeat() > HEARTBEAT_TIMEOUT_MS) {
                log.warn("AgentLifecycleManager: 心跳超时 instanceId={}, role={}, lastHeartbeat={}ms ago",
                        agent.getInstanceId(), agent.getRoleCode(), now - agent.getLastHeartbeat());
                problematic.add(agent);
                if (sink != null) {
                    sink.next(AgentEvent.builder()
                            .type("agent_timeout")
                            .metadata(java.util.Map.of(
                                    "instanceId", agent.getInstanceId(),
                                    "role", agent.getRoleCode(),
                                    "reason", "heartbeat_timeout"))
                            .build());
                }
                continue;
            }

            // 2. 全局超时检测
            if (now - agent.getStartedAt() > GLOBAL_TIMEOUT_MS) {
                log.warn("AgentLifecycleManager: 全局超时 instanceId={}, role={}, duration={}ms",
                        agent.getInstanceId(), agent.getRoleCode(), now - agent.getStartedAt());
                problematic.add(agent);
                if (sink != null) {
                    sink.next(AgentEvent.builder()
                            .type("agent_timeout")
                            .metadata(java.util.Map.of(
                                    "instanceId", agent.getInstanceId(),
                                    "role", agent.getRoleCode(),
                                    "reason", "global_timeout"))
                            .build());
                }
                continue;
            }

            // 3. 死锁检测
            if (agent.getWaitingForId() != null && detectDeadlock(agent.getInstanceId(), new java.util.HashSet<>())) {
                log.warn("AgentLifecycleManager: 检测到死锁 instanceId={}, role={}, waitingFor={}",
                        agent.getInstanceId(), agent.getRoleCode(), agent.getWaitingForId());
                problematic.add(agent);
                if (sink != null) {
                    sink.next(AgentEvent.builder()
                            .type("agent_deadlock")
                            .metadata(java.util.Map.of(
                                    "instanceId", agent.getInstanceId(),
                                    "role", agent.getRoleCode(),
                                    "waitingFor", agent.getWaitingForId()))
                            .build());
                }
            }
        }

        return problematic;
    }

    /**
     * 死锁检测：等待环检测（DFS 追踪等待链，发现环则判定死锁）。
     */
    private boolean detectDeadlock(String instanceId, Set<String> visited) {
        if (visited.size() > MAX_WAIT_DEPTH) return false;
        if (visited.contains(instanceId)) return true; // 检测到环
        visited.add(instanceId);

        AgentInstance agent = activeAgents.get(instanceId);
        if (agent == null || agent.getWaitingForId() == null) return false;
        return detectDeadlock(agent.getWaitingForId(), visited);
    }

    /**
     * 强制终止 Agent 实例。
     */
    public void terminate(String instanceId, String reason) {
        AgentInstance agent = activeAgents.remove(instanceId);
        if (agent != null) {
            agent.setStatus(AgentInstance.Status.TERMINATED);
            log.warn("AgentLifecycleManager.terminate: instanceId={}, role={}, reason={}",
                    instanceId, agent.getRoleCode(), reason);
        }
    }

    /**
     * 回收 Agent 实例：记录执行指标，释放资源。
     */
    public AgentInstance retire(String instanceId) {
        AgentInstance agent = activeAgents.remove(instanceId);
        if (agent != null) {
            agent.setStatus(AgentInstance.Status.COMPLETED);
            long duration = System.currentTimeMillis() - agent.getStartedAt();
            log.info("AgentLifecycleManager.retire: instanceId={}, role={}, duration={}ms",
                    instanceId, agent.getRoleCode(), duration);
        }
        return agent;
    }

    /**
     * 获取活跃 Agent 数量。
     */
    public int getActiveCount() {
        return activeAgents.size();
    }

    /**
     * Agent 实例状态。
     */
    public static class AgentInstance {
        private final String instanceId;
        private final String roleCode;
        private final String executionId;
        private final String collaborationId;
        private final long startedAt;
        private volatile long lastHeartbeat;
        private volatile Status status;
        private volatile String waitingForId;
        private volatile Long tokenConsumed;

        public enum Status {
            RUNNING, PAUSED, COMPLETED, FAILED, TERMINATED
        }

        public AgentInstance(String instanceId, String roleCode, String executionId,
                             String collaborationId, long startedAt, long lastHeartbeat,
                             Status status, String waitingForId, Long tokenConsumed) {
            this.instanceId = instanceId;
            this.roleCode = roleCode;
            this.executionId = executionId;
            this.collaborationId = collaborationId;
            this.startedAt = startedAt;
            this.lastHeartbeat = lastHeartbeat;
            this.status = status;
            this.waitingForId = waitingForId;
            this.tokenConsumed = tokenConsumed;
        }

        public String getInstanceId() { return instanceId; }
        public String getRoleCode() { return roleCode; }
        public String getExecutionId() { return executionId; }
        public String getCollaborationId() { return collaborationId; }
        public long getStartedAt() { return startedAt; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long t) { this.lastHeartbeat = t; }
        public Status getStatus() { return status; }
        public void setStatus(Status s) { this.status = s; }
        public String getWaitingForId() { return waitingForId; }
        public void setWaitingForId(String w) { this.waitingForId = w; }
        public Long getTokenConsumed() { return tokenConsumed; }
        public void setTokenConsumed(Long t) { this.tokenConsumed = t; }
    }
}