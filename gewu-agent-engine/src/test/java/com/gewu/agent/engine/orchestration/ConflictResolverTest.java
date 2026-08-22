package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.cognition.ArbiterEngine;
import com.gewu.agent.engine.cognition.NoOpReasoningKernel;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ConflictResolver} 四策略测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("冲突解决器")
class ConflictResolverTest {

    @Mock
    private HitlGateway hitlGateway;

    @Mock
    private ArbiterEngine arbiterEngine;

    private ConflictResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConflictResolver(new NoOpReasoningKernel(), hitlGateway, arbiterEngine);
        lenient().when(hitlGateway.requestApproval(any()))
                .thenReturn(Mono.just(HumanDecision.builder()
                        .decision("APPROVED").value("人工同意").operatorId("admin").build()));
    }

    private ConflictResolver.ConflictParty party(String agentId, String authority, String proposal) {
        return ConflictResolver.ConflictParty.builder()
                .agentId(agentId).authorityLevel(authority).proposal(proposal).build();
    }

    @Test
    @DisplayName("策略1 权威优先：coordinator > reviewer > executor")
    void authorityStrategy() {
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c1").type("authority").executionId("e1")
                .parties(List.of(
                        party("executor-1", "executor", "方案A"),
                        party("coordinator-1", "coordinator", "方案B"),
                        party("reviewer-1", "reviewer", "方案C")))
                .build();

        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution -> {
                    assertThat(resolution.getMethod()).isEqualTo("authority");
                    assertThat(resolution.getWinner().getAgentId()).isEqualTo("coordinator-1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("策略2 多数表决：相同提议过半数胜出")
    void majorityVoteStrategy() {
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c2").type("disagreement").executionId("e1")
                .parties(List.of(
                        party("a1", "executor", "方案X"),
                        party("a2", "executor", "方案X"),
                        party("a3", "executor", "方案Y")))
                .build();

        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution -> {
                    assertThat(resolution.getMethod()).isEqualTo("majority_vote");
                    assertThat(resolution.getWinner().getProposal()).isEqualTo("方案X");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("策略2 无多数时降级（未过半不表决胜出）")
    void majorityVoteRequiresMajority() {
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c3").type("disagreement").executionId("e1")
                .parties(List.of(
                        party("a1", "executor", "方案X"),
                        party("a2", "executor", "方案Y"),
                        party("a3", "executor", "方案Z")))
                .build();

        // 三方各一票（1/3 < 50%）-> 无法多数表决 -> 转 HITL
        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution ->
                        assertThat(resolution.getMethod()).isEqualTo("human_decision"))
                .verifyComplete();
    }

    @Test
    @DisplayName("策略3 LLM 仲裁：按 winnerIndex 选择胜出方")
    void llmArbitrationStrategy() {
        when(arbiterEngine.arbitrate(any(), any()))
                .thenReturn(ArbiterEngine.ArbitrationResult.of(1, "方案B更优", 0.9, "论证"));
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c4").type("semantic").executionId("e1")
                .parties(List.of(
                        party("a1", "executor", "方案A"),
                        party("a2", "executor", "方案B")))
                .build();

        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution -> {
                    assertThat(resolution.getMethod()).isEqualTo("llm_arbitration");
                    assertThat(resolution.getWinner().getAgentId()).isEqualTo("a2");
                    assertThat(resolution.getVerdict()).isEqualTo("方案B更优");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("策略3 仲裁异常时降级人工裁决")
    void llmArbitrationFailureFallsToHuman() {
        when(arbiterEngine.arbitrate(any(), any()))
                .thenThrow(new RuntimeException("仲裁服务不可用"));
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c5").type("semantic").executionId("e1")
                .parties(List.of(
                        party("a1", "executor", "方案A"),
                        party("a2", "executor", "方案B")))
                .build();

        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution ->
                        assertThat(resolution.getMethod()).isEqualTo("human_decision"))
                .verifyComplete();
    }

    @Test
    @DisplayName("策略4 人工裁决：批准取第一方，驳回标记 human_rejected")
    void humanDecisionStrategies() {
        var conflict = ConflictResolver.AgentConflict.builder()
                .conflictId("c6").type("unknown").executionId("e1")
                .parties(List.of(party("a1", "executor", "方案A")))
                .build();

        // 默认 mock 批准
        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution -> {
                    assertThat(resolution.getMethod()).isEqualTo("human_decision");
                    assertThat(resolution.getWinner().getAgentId()).isEqualTo("a1");
                })
                .verifyComplete();

        // 驳回场景
        when(hitlGateway.requestApproval(any()))
                .thenReturn(Mono.just(HumanDecision.builder().decision("REJECTED").build()));
        StepVerifier.create(resolver.resolve(conflict))
                .assertNext(resolution -> {
                    assertThat(resolution.getMethod()).isEqualTo("human_rejected");
                    assertThat(resolution.getWinner()).isNull();
                })
                .verifyComplete();
    }
}
