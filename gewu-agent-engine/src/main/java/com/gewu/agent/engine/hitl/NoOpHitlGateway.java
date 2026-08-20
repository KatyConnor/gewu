package com.gewu.agent.engine.hitl;

import reactor.core.publisher.Mono;

/**
 * {@link HitlGateway} 的 NoOp 默认实现 - 直接批准，不阻塞。
 * <p>适用于不需要人工协同的场景。
 *
 * @since 1.0.0
 */
public class NoOpHitlGateway implements HitlGateway {

    @Override
    public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
        return Mono.just(HumanDecision.builder()
                .decision("APPROVED")
                .operatorId("system")
                .build());
    }

    @Override
    public void submitDecision(String approvalId, HumanDecision decision) {
        // NoOp
    }
}