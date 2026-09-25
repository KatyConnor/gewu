package com.gewu.agent.engine.orchestration;

import java.util.Optional;

/**
 * 编排检查点持久化 SPI（WFO-03，EXEPLAN-ORCH-2026-09）。
 * <p>引擎本身不绑定数据库：{@link ExecutionControl} 默认内存注册表保存暂停检查点，
 * 进程重启即丢失。使用方（应用层）实现本接口注入持久化存储，
 * 使暂停检查点跨进程存活、断点续跑不依赖进程生命周期。
 * <p>实现约定：
 * <ul>
 *   <li>{@link #save} 双写场景幂等（同 executionId 覆盖）；失败仅告警，不得中断暂停流程</li>
 *   <li>{@link #load} 取出语义：命中即删（与内存 takeCheckpoint 对齐），恢复失败的兜底由执行记录终态负责</li>
 *   <li>{@link #delete} 对不存在记录静默成功（幂等）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface OrchestrationCheckpointStore {

    /** 空实现（默认）：引擎未注入持久层时退化为纯内存行为 */
    OrchestrationCheckpointStore NOOP = new OrchestrationCheckpointStore() {
        @Override
        public void save(String executionId, ExecutionControl.Checkpoint checkpoint) {
            // 纯内存模式：不持久化
        }

        @Override
        public Optional<ExecutionControl.Checkpoint> load(String executionId) {
            return Optional.empty();
        }

        @Override
        public void delete(String executionId) {
            // 纯内存模式：无持久化数据
        }

        @Override
        public boolean has(String executionId) {
            return false;
        }
    };

    /** 保存（覆盖式）暂停检查点 */
    void save(String executionId, ExecutionControl.Checkpoint checkpoint);

    /** 取出检查点（命中即删，无检查点返回 empty） */
    Optional<ExecutionControl.Checkpoint> load(String executionId);

    /** 删除检查点（幂等） */
    void delete(String executionId);

    /** 是否存在检查点 */
    boolean has(String executionId);
}
