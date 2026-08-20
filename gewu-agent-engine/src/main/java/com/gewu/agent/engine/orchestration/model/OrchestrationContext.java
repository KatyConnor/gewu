package com.gewu.agent.engine.orchestration.model;

import com.gewu.agent.engine.orchestration.VersionedContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 编排执行上下文 - 贯穿一次编排图执行的运行时状态。
 * <p>图变量由 {@link VersionedContext} 版本化管理：每次 {@link #putVariable}
 * copy-on-write 生成新版本，支持 {@link #rollbackTo} 回滚与 {@link #snapshotVariables} 快照，
 * 供 HITL 驳回回滚、执行取消状态留痕等场景使用。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationContext {

    /** 执行实例 ID */
    private String executionId;
    /** 图 ID */
    private String graphId;
    /** 操作用户 */
    private String userId;
    /** 租户标识 */
    private String tenantId;
    /** 会话标识 */
    private String sessionId;
    /** 图变量初始值（运行时读写统一经 {@link #putVariable}/{@link #getVariable} 版本化路径） */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();
    /** 当前节点 ID */
    private String currentNodeId;
    /** 自主循环迭代数 */
    private int iteration;
    /** 版本化状态（懒初始化，不参与序列化；Builder 未设置时保持 null，由 state() 兜底） */
    private transient volatile VersionedContext versionedState;

    private VersionedContext state() {
        if (versionedState == null) {
            synchronized (this) {
                if (versionedState == null) {
                    versionedState = new VersionedContext(variables != null ? variables : new HashMap<>());
                }
            }
        }
        return versionedState;
    }

    /** 当前版本变量视图（兼容直接 Map 读取；写入请走 {@link #putVariable} 以生成版本） */
    public Map<String, Object> getVariables() {
        return state().asMap();
    }

    /** 重置变量（初始化版本化状态，版本号归零） */
    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
        this.versionedState = new VersionedContext(variables);
    }

    /** 写入节点产出到图变量（copy-on-write，生成新版本） */
    public int putVariable(String key, Object value) {
        return state().putVariable(key, value);
    }

    /** 读取图变量 */
    public Object getVariable(String key) {
        return state().getVariable(key);
    }

    /** 当前状态版本号 */
    public int getStateVersion() {
        return state().getVersion();
    }

    /** 回滚到指定版本（HITL 驳回 / 执行取消场景） */
    public void rollbackTo(int version) {
        state().rollback(version);
    }

    /** 回滚到上一版本 */
    public boolean rollbackOneVersion() {
        return state().rollbackOne();
    }

    /** 当前状态的不可变快照 */
    public Map<String, Object> snapshotVariables() {
        return state().snapshot();
    }
}
