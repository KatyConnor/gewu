package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.cognition.ArbiterEngine;
import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 冲突解决器 - 按优先级使用四种策略。
 * <ol>
 *   <li>权威优先：reviewer > coordinator > executor</li>
 *   <li>多数表决：多个同级 Agent 投票，>50% 通过</li>
 *   <li>LLM 仲裁：同厂商多采样仲裁</li>
 *   <li>人工裁决：无法自动解决时转 HITL</li>
 * </ol>
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ConflictResolver {

    private final ReasoningKernel reasoningKernel;
    private final HitlGateway hitlGateway;
    private final ArbiterEngine arbiterEngine;

    /**
     * 解决冲突。
     */
    public Mono<ConflictResolution> resolve(AgentConflict conflict) {
        // 策略1: 权威优先
        if ("authority".equals(conflict.getType())) {
            ConflictParty winner = conflict.getParties().stream()
                    .max((a, b) -> Integer.compare(authorityRank(a.getAuthorityLevel()), authorityRank(b.getAuthorityLevel())))
                    .orElse(null);
            if (winner != null) {
                log.debug("ConflictResolver 权威优先: winner={}", winner.getAgentId());
                return Mono.just(ConflictResolution.builder()
                        .winner(winner).method("authority").build());
            }
        }

        // 策略2: 多数表决
        if ("disagreement".equals(conflict.getType()) && conflict.getParties().size() > 1) {
            Map<String, Integer> votes = new HashMap<>();
            Map<String, ConflictParty> proposalMap = new HashMap<>();
            for (ConflictParty party : conflict.getParties()) {
                String key = party.getProposal() != null ? party.getProposal().hashCode() + "" : "null";
                votes.merge(key, 1, Integer::sum);
                proposalMap.putIfAbsent(key, party);
            }
            String maxKey = votes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (maxKey != null && votes.get(maxKey) > conflict.getParties().size() / 2) {
                log.debug("ConflictResolver 多数表决: winner={}", proposalMap.get(maxKey).getAgentId());
                return Mono.just(ConflictResolution.builder()
                        .winner(proposalMap.get(maxKey)).method("majority_vote").build());
            }
        }

        // 策略3: LLM 仲裁（委托 ArbiterEngine 多采样仲裁，使用真实 winnerIndex）
        if ("semantic".equals(conflict.getType())) {
            try {
                String proposals = conflict.getParties().stream()
                        .map(p -> p != null && p.getProposal() != null ? p.getProposal() : "")
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                var arbitration = arbiterEngine.arbitrate(
                        "冲突场景: " + conflict.getDescription(), List.of(proposals.split("\n")));
                int winnerIdx = arbitration.getWinnerIndex();
                if (winnerIdx >= 0 && winnerIdx < conflict.getParties().size()) {
                    ConflictParty winner = conflict.getParties().get(winnerIdx);
                    log.debug("ConflictResolver LLM仲裁: winner={}, confidence={}",
                            winner.getAgentId(), arbitration.getConfidence());
                    return Mono.just(ConflictResolution.builder()
                            .winner(winner).method("llm_arbitration")
                            .verdict(arbitration.getDecision()).build());
                }
            } catch (Exception e) {
                log.debug("ConflictResolver LLM仲裁失败: {}", e.getMessage());
            }
        }

        // 策略4: 人工裁决
        log.warn("ConflictResolver 无法自动解决，转人工: conflictId={}", conflict.getConflictId());
        return hitlGateway.requestApproval(
                        com.gewu.agent.engine.hitl.ApprovalRequest.builder()
                                .executionId(conflict.getExecutionId())
                                .nodeId(conflict.getConflictId())
                                .type("APPROVE_REJECT")
                                .summary("多Agent冲突需人工裁决: " + conflict.getDescription())
                                .timeoutSeconds(1800)
                                .build())
                .map(decision -> {
                    if ("APPROVED".equals(decision.getDecision())) {
                        return ConflictResolution.builder()
                                .winner(conflict.getParties().get(0))
                                .method("human_decision")
                                .verdict(decision.getValue())
                                .build();
                    } else {
                        return ConflictResolution.builder()
                                .method("human_rejected")
                                .verdict(decision.getValue() != null ? decision.getValue() : "人工驳回")
                                .build();
                    }
                });
    }

    private int authorityRank(String level) {
        if (level == null) return 0;
        return switch (level) {
            case "executor" -> 1;
            case "reviewer" -> 2;
            case "coordinator" -> 3;
            default -> 0;
        };
    }

    /**
     * 冲突描述。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentConflict {
        private String conflictId;
        /** 冲突类型: authority / disagreement / semantic */
        private String type;
        private String description;
        private String executionId;
        private List<ConflictParty> parties;
    }

    /**
     * 冲突方。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictParty {
        private String agentId;
        /** 权威级别: executor / reviewer / coordinator */
        private String authorityLevel;
        /** 提议方案 */
        private String proposal;
    }

    /**
     * 冲突解决结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictResolution {
        /** 胜出方 */
        private ConflictParty winner;
        /** 解决方式: authority / majority_vote / llm_arbitration / human_decision / human_rejected */
        private String method;
        /** 裁决说明 */
        private String verdict;
    }
}