package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;

/**
 * 子图解析 SPI（WFO-04，EXEPLAN-ORCH-2026-09）。
 * <p>引擎不绑定数据库：SUBGRAPH 节点按 refId 加载子图定义由使用方实现本接口注入。
 * 实现约定：仅返回可执行（active 状态、优先版本快照）的子图定义；
 * 目标不存在或不可执行返回 {@code null}（引擎按节点失败处理）。
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface SubgraphResolver {

    /**
     * 按 refId 解析可执行的子图定义。
     *
     * @param graphId 子图 refId
     * @return 可执行子图定义；不存在或非 active 返回 null
     * @throws Exception 加载/反序列化异常（引擎按节点失败处理）
     */
    OrchestrationGraph resolve(String graphId) throws Exception;
}
