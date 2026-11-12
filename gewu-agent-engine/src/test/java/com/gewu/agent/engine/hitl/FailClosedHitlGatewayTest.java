package com.gewu.agent.engine.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailClosedHitlGateway} AE-P0-4 测试：审批请求一律拒绝（fail-closed）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-12
 */
@DisplayName("fail-closed 审批网关")
class FailClosedHitlGatewayTest {

    private final FailClosedHitlGateway gateway = new FailClosedHitlGateway();

    @Test
    @DisplayName("审批请求返回 REJECTED 并说明 fail-closed 原因（负向测试：摘除真实网关后不得放行）")
    void requestApprovalIsRejected() {
        ApprovalRequest request = ApprovalRequest.builder()
                .approvalId("ap-1").executionId("e-1").nodeId("n1")
                .type("APPROVE_REJECT").summary("危险操作审批").timeoutSeconds(60)
                .build();

        StepVerifier.create(gateway.requestApproval(request))
                .assertNext(decision -> {
                    assertThat(decision.getDecision()).isEqualTo("REJECTED");
                    assertThat(decision.getValue()).contains("fail-closed");
                    assertThat(decision.getOperatorId()).isEqualTo("system");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("提交决策为无操作（无真实审批存储）")
    void submitDecisionIsNoOp() {
        gateway.submitDecision("ap-1", HumanDecision.builder()
                .decision("APPROVED").operatorId("u1").build());
        // 无异常即通过
    }
}
