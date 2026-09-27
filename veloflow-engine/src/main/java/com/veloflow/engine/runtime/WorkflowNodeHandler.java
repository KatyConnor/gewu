package com.veloflow.engine.runtime;

import java.util.List;

/**
 * 工作流节点处理器（51 号 §一原则二：节点注册表）。
 * <p>每种节点类型一个实现，启动时注册进 {@link WorkflowNodeHandlerRegistry}；
 * 新增节点类型 = 新增 Handler 类，不改调度内核。
 * <p>两型语义：
 * <ul>
 *   <li>{@link NodeKind#AUTO} 自动型：激活即执行（同步快速节点在调用线程，慢节点经
 *       {@link WorkflowNodeContext#complete} 异步回调），完成即推进</li>
 *   <li>{@link NodeKind#WAITING} 等待型：激活即挂起（登记 timeout_at），由人工动作/
 *       定时器到期经 {@link WorkflowNodeContext#complete} 驱动推进</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface WorkflowNodeHandler {

    /** 节点类型键（与 workflow_node.node_type 一致） */
    String type();

    /** 节点类型形态 */
    NodeKind kind();

    /**
     * 必填 config 字段（WV-06 校验元数据，由注册表统一驱动）。
     * 返回空列表表示无必填项。
     */
    default List<String> requiredConfigFields() {
        return List.of();
    }

    /**
     * 激活节点：等待型登记超时后返回（等待外部事件）；自动型执行业务，
     * 结果经 {@link WorkflowNodeContext#complete} 回调（可同步可异步）。
     * <p>实现约定：不得抛出异常终止调度——失败请走
     * {@link WorkflowNodeContext#complete}（success=false）以进入错误处理语义。
     */
    void activate(WorkflowNodeContext context);

    /** 节点形态 */
    enum NodeKind {
        /** 自动型：激活即执行，完成回调推进 */
        AUTO,
        /** 等待型：激活即挂起，外部事件驱动 */
        WAITING
    }
}
