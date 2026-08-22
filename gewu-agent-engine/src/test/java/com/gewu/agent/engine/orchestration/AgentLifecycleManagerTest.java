package com.gewu.agent.engine.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentLifecycleManager} 心跳/超时/死锁检测测试。
 */
@DisplayName("Agent 生命周期管理器")
class AgentLifecycleManagerTest {

    @Test
    @DisplayName("spawn 注册实例，retire 完成并移除")
    void spawnAndRetire() {
        AgentLifecycleManager manager = new AgentLifecycleManager(60000, 300000);
        var agent = manager.spawn("DEVELOPER", "exec-1", "collab-1");

        assertThat(manager.getActiveCount()).isEqualTo(1);
        assertThat(agent.getStatus()).isEqualTo(AgentLifecycleManager.AgentInstance.Status.RUNNING);

        var retired = manager.retire(agent.getInstanceId());
        assertThat(retired.getStatus()).isEqualTo(AgentLifecycleManager.AgentInstance.Status.COMPLETED);
        assertThat(manager.getActiveCount()).isZero();
        assertThat(manager.retire("不存在")).isNull();
    }

    @Test
    @DisplayName("心跳超时检测：超时实例进入问题列表")
    void heartbeatTimeoutDetected() throws Exception {
        AgentLifecycleManager manager = new AgentLifecycleManager(50, 300000);
        var agent = manager.spawn("DEVELOPER", "exec-1", null);
        Thread.sleep(120);

        List<AgentLifecycleManager.AgentInstance> problematic = manager.monitor(null);

        assertThat(problematic).extracting(AgentLifecycleManager.AgentInstance::getInstanceId)
                .contains(agent.getInstanceId());

        // 心跳刷新后不再超时
        manager.heartbeat(agent.getInstanceId());
        assertThat(manager.monitor(null)).isEmpty();
    }

    @Test
    @DisplayName("全局超时检测：运行时长超限进入问题列表")
    void globalTimeoutDetected() throws Exception {
        AgentLifecycleManager manager = new AgentLifecycleManager(60000, 50);
        manager.spawn("DEVELOPER", "exec-1", null);
        Thread.sleep(120);

        List<AgentLifecycleManager.AgentInstance> problematic = manager.monitor(null);

        assertThat(problematic).hasSize(1);
    }

    @Test
    @DisplayName("死锁检测：循环等待环被发现，清除等待后恢复")
    void deadlockDetectionAndRecovery() {
        AgentLifecycleManager manager = new AgentLifecycleManager(60000, 300000);
        var a = manager.spawn("ARCHITECT", "exec-1", null);
        var b = manager.spawn("DEVELOPER", "exec-1", null);

        // a 等 b，b 等 a -> 环
        manager.markWaiting(a.getInstanceId(), b.getInstanceId());
        manager.markWaiting(b.getInstanceId(), a.getInstanceId());

        List<AgentLifecycleManager.AgentInstance> problematic = manager.monitor(null);
        assertThat(problematic).hasSize(2);

        // 解除等待后无死锁
        manager.clearWaiting(a.getInstanceId());
        manager.clearWaiting(b.getInstanceId());
        assertThat(manager.monitor(null)).isEmpty();
    }

    @Test
    @DisplayName("非环等待链不误报死锁")
    void waitingChainWithoutCycleNotDeadlock() {
        AgentLifecycleManager manager = new AgentLifecycleManager(60000, 300000);
        var a = manager.spawn("ARCHITECT", "exec-1", null);
        var b = manager.spawn("DEVELOPER", "exec-1", null);

        // a 等 b，b 不等任何人 -> 无环
        manager.markWaiting(a.getInstanceId(), b.getInstanceId());

        assertThat(manager.monitor(null)).isEmpty();
    }

    @Test
    @DisplayName("terminate 强制终止并移除注册")
    void terminateRemovesInstance() {
        AgentLifecycleManager manager = new AgentLifecycleManager(60000, 300000);
        var agent = manager.spawn("DEVELOPER", "exec-1", null);

        manager.terminate(agent.getInstanceId(), "失控防护");

        assertThat(agent.getStatus()).isEqualTo(AgentLifecycleManager.AgentInstance.Status.TERMINATED);
        assertThat(manager.getActiveCount()).isZero();
    }
}
