package com.gewu.application.workflow.engine;

import com.gewu.domain.workflow.WorkflowInstance;
import com.gewu.domain.workflow.WorkflowNode;
import com.gewu.domain.workflow.WorkflowNodeInstance;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 节点激活上下文——Handler 与调度器交互的唯一通道（51 号 §四）。
 * <p>提供只读视图（实例/节点/节点实例/变量）与完成回调；
 * {@link #complete} 幂等（状态机原子更新吞掉重复回调）。
 *
 * @since 1.0.0
 */
public interface WorkflowNodeContext {

    /** 工作流实例（只读快照） */
    WorkflowInstance instance();

    /** 节点定义（只读） */
    WorkflowNode node();

    /** 本分支的节点实例行（已插入，status 由调度器按 NodeKind 初始化） */
    WorkflowNodeInstance nodeInstance();

    /** 当前变量空间（只读快照：trigger 输入 / 业务变量 / 前驱节点输出） */
    Map<String, Object> variables();

    /** 解析后的节点 config（空 config 返回空 Map） */
    Map<String, Object> config();

    /** 本分支上游输出（激活时传入：串行链=前驱 output；并行分支=parallel 透传） */
    String upstreamOutput();

    /**
     * 节点完成回调（幂等）：写 output、推 node_instance 终态，并驱动调度器推进。
     *
     * @param success 成功完成 / 失败（进入 onError 语义：默认实例 failed）
     * @param outputJson 节点输出 JSON 字符串（失败时为错误信息）
     */
    void complete(boolean success, String outputJson);

    /** 完成回调的便捷形式（成功） */
    default void complete(String outputJson) {
        complete(true, outputJson);
    }

    /** 调度器内部装配用（不对外） */
    static WorkflowNodeContext of(WorkflowInstance instance, WorkflowNode node,
                                  WorkflowNodeInstance nodeInstance, Map<String, Object> variables,
                                  Map<String, Object> config, String upstreamOutput,
                                  Consumer<Completion> completionSink) {
        return new WorkflowNodeContext() {
            @Override public WorkflowInstance instance() { return instance; }
            @Override public WorkflowNode node() { return node; }
            @Override public WorkflowNodeInstance nodeInstance() { return nodeInstance; }
            @Override public Map<String, Object> variables() { return variables; }
            @Override public Map<String, Object> config() { return config; }
            @Override public String upstreamOutput() { return upstreamOutput; }
            @Override public void complete(boolean success, String outputJson) {
                completionSink.accept(new Completion(nodeInstance.getId(), success, outputJson));
            }
        };
    }

    /** 完成回调载荷 */
    record Completion(String nodeInstanceId, boolean success, String outputJson) {
    }

    /** config 解析辅助 */
    static Map<String, Object> emptyConfig() {
        return Map.of();
    }
}
