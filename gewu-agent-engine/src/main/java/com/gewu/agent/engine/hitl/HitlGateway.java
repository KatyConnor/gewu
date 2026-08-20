package com.gewu.agent.engine.hitl;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 人机协同网关 SPI - 审批 / 接管 / 回退的请求与恢复。
 * <p>使用方实现此接口对接审批队列 / IM 通知 / Webhook 等渠道。
 * 框架提供 {@link NoOpHitlGateway} 默认实现（直接批准，不阻塞），适用于无 HITL 场景。
 *
 * @since 1.0.0
 */
public interface HitlGateway {

    /**
     * 请求人工审批。
     * <p>阻塞当前节点，异步等待决策。返回 Mono 在 submitDecision 到达时完成。
     */
    Mono<HumanDecision> requestApproval(ApprovalRequest request);

    /** 提交人工决策（恢复执行） */
    void submitDecision(String approvalId, HumanDecision decision);

    /** 人工接管（直接控制执行） */
    default void takeover(String executionId, String operatorId) {
    }

    /** 人工回退到某节点 */
    default void rollback(String executionId, String toNodeId) {
    }
}