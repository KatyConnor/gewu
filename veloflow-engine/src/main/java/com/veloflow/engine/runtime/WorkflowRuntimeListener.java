package com.veloflow.engine.runtime;

import com.veloflow.engine.persistence.model.WorkflowInstance;

/**
 * 运行时监听器 SPI（P3 二期）：调度器在关键生命周期点回调，宿主/触发层据此联动
 * （webhook 同步返回、事件订阅清理、上游触发链）。引擎内零强制依赖——无监听器时
 * 调度器行为不变；多实现全部回调。
 *
 * @since 1.0.0
 */
public interface WorkflowRuntimeListener {

    /** respond 节点完成：payload 已落实例 respond_payload 列，供同步返回链路取用 */
    default void onRespond(String instanceId, String payloadJson) {
    }

    /** event-wait 节点离开等待（完成/超时/失败）：清理该节点残留订阅（幂等） */
    default void onEventWaitNodeSettled(String instanceId, String nodeInstanceId) {
    }

    /** 实例成功完成（全部无活动分支）：供 upstream-trigger 联动发起下游流程 */
    default void onInstanceCompleted(WorkflowInstance instance) {
    }

    /** 实例失败/终止：清理该实例全部 waiting 订阅（幂等） */
    default void onInstanceCancelled(WorkflowInstance instance) {
    }
}
