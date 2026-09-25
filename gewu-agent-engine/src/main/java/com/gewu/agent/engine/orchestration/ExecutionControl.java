package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行控制注册表 - 在途编排执行的协作式暂停/取消信号与断点检查点管理。
 * <p>检查点采用「内存 + 可选持久层」双写策略（WFO-03）：内存注册表为主（同进程恢复），
 * 注入 {@link OrchestrationCheckpointStore} 时同步持久化（跨进程恢复，进程重启不丢）；
 * 读取顺序内存优先，内存未命中（进程重启）回退持久层。
 * <p>信号为协作式：Pipeline 在节点间检查信号，当前节点执行完毕后生效；
 * HUMAN 审批等待中的执行无法被信号中断（审批超时自身即为保护）。
 *
 * @since 1.0.0
 */
@Slf4j
public class ExecutionControl {

    /** 控制信号 */
    public enum Signal {
        NONE, PAUSE, CANCEL
    }

    /** 在途执行状态：信号 + 暂停检查点 */
    static class ExecutionState {
        volatile Signal signal = Signal.NONE;
        volatile Checkpoint checkpoint;
    }

    /** 暂停检查点：恢复所需的图、上下文与恢复起始节点 */
    public record Checkpoint(OrchestrationGraph graph, OrchestrationContext context, String resumeFromNodeId) {
    }

    private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();
    /** 检查点持久层（默认 NOOP：纯内存行为，与历史版本一致） */
    private final OrchestrationCheckpointStore checkpointStore;

    public ExecutionControl() {
        this(OrchestrationCheckpointStore.NOOP);
    }

    public ExecutionControl(OrchestrationCheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore != null ? checkpointStore : OrchestrationCheckpointStore.NOOP;
    }

    /** 请求暂停（幂等；未知执行 ID 忽略——可能已结束或由外部状态机管理） */
    public void requestPause(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state != null) {
            state.signal = Signal.PAUSE;
            log.info("ExecutionControl: 请求暂停 executionId={}", executionId);
        } else {
            log.warn("ExecutionControl: 暂停未知执行（可能已结束）: {}", executionId);
        }
    }

    /** 请求取消（幂等） */
    public void requestCancel(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state != null) {
            state.signal = Signal.CANCEL;
            log.info("ExecutionControl: 请求取消 executionId={}", executionId);
        } else {
            log.warn("ExecutionControl: 取消未知执行（可能已结束）: {}", executionId);
        }
    }

    /** 查询执行信号（未注册返回 NONE） */
    public Signal signalOf(String executionId) {
        ExecutionState state = executions.get(executionId);
        return state != null ? state.signal : Signal.NONE;
    }

    /** 清除信号（信号生效后被遍历器消费，避免恢复后残留旧信号） */
    public void clearSignal(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state != null) {
            state.signal = Signal.NONE;
        }
    }

    /** 注册在途执行（执行开始时调用） */
    public void register(String executionId) {
        executions.putIfAbsent(executionId, new ExecutionState());
    }

    /** 注销执行（正常完成/失败/取消后调用，同时清除检查点） */
    public void unregister(String executionId) {
        executions.remove(executionId);
        try {
            checkpointStore.delete(executionId);
        } catch (Exception e) {
            log.warn("ExecutionControl: 持久检查点删除失败（忽略）: {} - {}", executionId, e.getMessage());
        }
    }

    /** 保存暂停检查点（暂停生效时由遍历器写入；内存 + 持久层双写） */
    public void saveCheckpoint(String executionId, Checkpoint checkpoint) {
        ExecutionState state = executions.get(executionId);
        if (state != null) {
            state.checkpoint = checkpoint;
        }
        try {
            checkpointStore.save(executionId, checkpoint);
        } catch (Exception e) {
            // 持久化失败不阻断暂停流程：内存检查点仍然有效（仅进程重启后不可恢复）
            log.warn("ExecutionControl: 持久检查点保存失败（同进程恢复不受影响）: {} - {}", executionId, e.getMessage());
        }
        log.info("ExecutionControl: 保存暂停检查点 executionId={}, resumeFromNode={}",
                executionId, checkpoint.resumeFromNodeId());
    }

    /** 取出暂停检查点（恢复时取出并清除；内存优先，未命中回退持久层） */
    public Optional<Checkpoint> takeCheckpoint(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state != null && state.checkpoint != null) {
            Checkpoint checkpoint = state.checkpoint;
            state.checkpoint = null;
            deletePersistedQuietly(executionId);
            return Optional.of(checkpoint);
        }
        // 内存未命中（典型场景：进程重启后恢复）——回退持久层，load 即删
        try {
            Optional<Checkpoint> persisted = checkpointStore.load(executionId);
            persisted.ifPresent(cp -> deletePersistedQuietly(executionId));
            if (persisted.isPresent()) {
                log.info("ExecutionControl: 从持久层恢复检查点: executionId={}", executionId);
            }
            return persisted;
        } catch (Exception e) {
            log.warn("ExecutionControl: 持久检查点读取失败: {} - {}", executionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 是否存在暂停检查点（恢复前探测；内存或持久层任一命中即可恢复） */
    public boolean hasCheckpoint(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state != null && state.checkpoint != null) {
            return true;
        }
        try {
            return checkpointStore.has(executionId);
        } catch (Exception e) {
            log.warn("ExecutionControl: 持久检查点探测失败: {} - {}", executionId, e.getMessage());
            return false;
        }
    }

    private void deletePersistedQuietly(String executionId) {
        try {
            checkpointStore.delete(executionId);
        } catch (Exception e) {
            log.warn("ExecutionControl: 持久检查点删除失败（忽略）: {} - {}", executionId, e.getMessage());
        }
    }
}
