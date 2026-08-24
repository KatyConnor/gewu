package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteConditionEvaluator} 路由条件求值测试（T3.1）。
 */
@DisplayName("路由条件求值器")
class RouteConditionEvaluatorTest {

    private final RouteConditionEvaluator evaluator = new RouteConditionEvaluator();
    private OrchestrationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = OrchestrationContext.builder().executionId("exec-1").build();
        ctx.putVariable("status", "approved");
        ctx.putVariable("score", "85");
        ctx.putVariable("title", "架构设计方案V2");
    }

    private GraphEdge edge(String condition, String to) {
        return GraphEdge.builder().condition(condition).toNode(to).build();
    }

    @Test
    @DisplayName("var:x == 'y' 字符串等值命中")
    void equalsCondition() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:status == 'rejected'", "node-reject"),
                edge("var:status == 'approved'", "node-approve"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-approve");
    }

    @Test
    @DisplayName("数字比较：'85' == '85.0' 按数值相等")
    void numericComparison() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:score == '85.0'", "node-high"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-high");
    }

    @Test
    @DisplayName("var:x != 'y' 不等命中")
    void notEqualsCondition() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:status != 'pending'", "node-continue"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-continue");
    }

    @Test
    @DisplayName("contains 子串命中")
    void containsCondition() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:title contains '架构'", "node-arch"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-arch");
    }

    @Test
    @DisplayName("裸变量：存在且非空即命中")
    void bareVariableCondition() {
        ctx.putVariable("blockingIssue", "true");
        var selected = evaluator.selectEdge(List.of(
                edge("var:blockingIssue", "node-blocked"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-blocked");
    }

    @Test
    @DisplayName("全部条件未命中回退 else 默认边")
    void fallbackToDefaultEdge() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:status == 'rejected'", "node-reject"),
                edge("var:score == '0'", "node-zero"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-default");
    }

    @Test
    @DisplayName("条件未命中的变量视为不匹配（空/null 安全）")
    void nullVariableNeverMatches() {
        var selected = evaluator.selectEdge(List.of(
                edge("var:missing == 'x'", "node-x"),
                edge("var:missing", "node-any"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-default");
    }

    @Test
    @DisplayName("无条件与空出边列表的处理")
    void nullAndEmptyEdges() {
        assertThat(evaluator.selectEdge(null, ctx)).isNull();
        assertThat(evaluator.selectEdge(List.of(), ctx)).isNull();
        // 无任何默认边且条件全不命中 -> null（遍历层判失败）
        assertThat(evaluator.selectEdge(List.of(edge("var:status == 'x'", "node-x")), ctx)).isNull();
        // 无条件边视为默认边
        assertThat(evaluator.selectEdge(List.of(edge(null, "node-fallback")), ctx).getToNode())
                .isEqualTo("node-fallback");
    }

    @Test
    @DisplayName("无法识别的条件语法视为不命中")
    void unrecognizedSyntaxNotMatched() {
        var selected = evaluator.selectEdge(List.of(
                edge("1 + 1 == 2", "node-eval-injection"),
                edge("else", "node-default")), ctx);
        assertThat(selected.getToNode()).isEqualTo("node-default");
    }
}
