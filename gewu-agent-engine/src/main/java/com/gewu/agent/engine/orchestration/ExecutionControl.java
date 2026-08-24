package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行控制注册表 - 在途编排执行的协作式暂停/取消信号与断点检查点管理。
 * <p>引擎不绑定数据库：暂停检查点（图 + 上下文 + 恢复节点）保存在内存注册表，
 * 进程重启后由使用方从 ExecutionRecord 快照（graph_snapshot/variables/current_node_id）重建。
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
    }

    /** 保存暂停检查点（暂停生效时由遍历器写入） */
    public void saveCheckpoint(String executionId, Checkpoint checkpoint) {
        ExecutionState state = executions.get(executionId);
        if (state != null) {
            state.checkpoint = checkpoint;
            log.info("ExecutionControl: 保存暂停检查点 executionId={}, resumeFromNode={}",
                    executionId, checkpoint.resumeFromNodeId());
        }
    }

    /** 取出暂停检查点（恢复时取出并清除；无检查点返回 empty） */
    public Optional<Checkpoint> takeCheckpoint(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state == null || state.checkpoint == null) {
            return Optional.empty();
        }
        Checkpoint checkpoint = state.checkpoint;
        state.checkpoint = null;
        return Optional.of(checkpoint);
    }

    /** 是否存在暂停检查点（恢复前探测） */
    public boolean hasCheckpoint(String executionId) {
        ExecutionState state = executions.get(executionId);
        return state != null && state.checkpoint != null;
    }
}
