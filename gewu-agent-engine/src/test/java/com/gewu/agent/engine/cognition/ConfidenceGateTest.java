package com.gewu.agent.engine.cognition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ConfidenceGate} 加权置信评估与四级决策测试。
 */
@DisplayName("置信度门控")
class ConfidenceGateTest {

    private final ConfidenceGate gate = new ConfidenceGate();

    @Test
    @DisplayName("全维度高分 -> ADOPT")
    void highConfidenceAdopts() {
        var decision = gate.evaluate(0.95, 1.0, 0.9, 1.0);
        assertThat(decision.getAction()).isEqualTo(GateDecision.Action.ADOPT);
        assertThat(decision.getScore()).isCloseTo(0.4 * 0.95 + 0.25 * 1.0 + 0.2 * 0.9 + 0.15 * 1.0,
                within(0.001));
    }

    @Test
    @DisplayName("中等置信 -> RETRY_CHANGE_MODEL")
    void mediumConfidenceRetriesModel() {
        var decision = gate.evaluate(0.6, 0.8, 0.5, 0.8);
        assertThat(decision.getAction()).isEqualTo(GateDecision.Action.RETRY_CHANGE_MODEL);
    }

    @Test
    @DisplayName("偏低置信 -> RETRY_CHANGE_CONTEXT")
    void lowConfidenceRetriesContext() {
        var decision = gate.evaluate(0.4, 0.5, 0.4, 0.4);
        assertThat(decision.getAction()).isEqualTo(GateDecision.Action.RETRY_CHANGE_CONTEXT);
    }

    @Test
    @DisplayName("极低置信 -> ESCALATE_HITL")
    void veryLowConfidenceEscalates() {
        var decision = gate.evaluate(0.1, 0.2, 0.1, 0.1);
        assertThat(decision.getAction()).isEqualTo(GateDecision.Action.ESCALATE_HITL);
    }

    @Test
    @DisplayName("越界输入被 clamp 到 [0,1]")
    void outOfRangeClamped() {
        var decision = gate.evaluate(5.0, -3.0, 2.0, 1.0);
        // clamp 后 (1, 0, 1, 1) -> 0.4*1+0.25*0+0.2*1+0.15*1 = 0.75 -> RETRY_CHANGE_MODEL
        assertThat(decision.getScore()).isCloseTo(0.75, within(0.001));
        assertThat(decision.getAction()).isEqualTo(GateDecision.Action.RETRY_CHANGE_MODEL);
    }

    @Test
    @DisplayName("简化重载：仅 Critic 评分（其余取默认）")
    void simplifiedOverload() {
        // evaluate(critic) = evaluate(critic, 1.0, 0.5, 1.0)，固定项贡献 0.5
        var adopt = gate.evaluate(0.95);
        assertThat(adopt.getAction()).isEqualTo(GateDecision.Action.ADOPT);
        // critic=0.1 时总分 0.4*0.1+0.5=0.54 -> RETRY_CHANGE_CONTEXT（下限受固定项约束）
        var retryContext = gate.evaluate(0.1);
        assertThat(retryContext.getAction()).isEqualTo(GateDecision.Action.RETRY_CHANGE_CONTEXT);
        assertThat(retryContext.getScore()).isCloseTo(0.54, within(0.001));
    }
}
