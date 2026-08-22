package com.gewu.agent.engine.cognition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ComplexityRouter} 复杂度评估与等级映射测试。
 */
@DisplayName("复杂度路由器")
class ComplexityRouterTest {

    private final ComplexityRouter router = new ComplexityRouter(new DualSystemRouter());

    private PerceptionEngine.Intent intent(String type, int entities, boolean multiStep) {
        return PerceptionEngine.Intent.builder()
                .intentType(type).entityCount(entities).requiresMultiStep(multiStep)
                .build();
    }

    @Test
    @DisplayName("问候意图 -> System 1 + L1")
    void greetingIsL1() {
        var result = router.route("你好", intent("greeting", 0, false));
        assertThat(result.getLevel()).isEqualTo("L1");
        assertThat(result.getSystemChoice().isSystem1()).isTrue();
        assertThat(result.getScore()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("设计意图 + 多步 + 实体多 -> 高分 L3")
    void designMultiStepIsL3() {
        var result = router.route("请完成系统架构设计并输出部署方案", intent("design", 6, true));
        assertThat(result.getLevel()).isEqualTo("L3");
        assertThat(result.getSystemChoice().isSystem1()).isFalse();
        assertThat(result.getScore()).isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("中等任务映射 L2")
    void mediumTaskIsL2() {
        // 查询(+1)+生成(+2) -> DualSystem 复杂度 3 -> System 2；综合评分 4 < 7 -> L2
        var result = router.route("查询任务状态并生成统计报表", intent("task_query", 2, false));
        assertThat(result.getLevel()).isEqualTo("L2");
        assertThat(result.getSystemChoice().isSystem1()).isFalse();
    }

    @Test
    @DisplayName("评分上限封顶 10")
    void scoreCappedAt10() {
        String longDesc = "架构重构".repeat(200);
        var result = router.route(longDesc, intent("deploy", 20, true));
        assertThat(result.getScore()).isEqualTo(10);
    }

    @Test
    @DisplayName("null 意图按 unknown 处理，简化重载可用")
    void nullIntentAndSimplifiedOverload() {
        var result = router.route("你好");
        assertThat(result).isNotNull();
        assertThat(result.getSystemChoice().isSystem1()).isTrue();
    }

    @Test
    @DisplayName("System 1 决策直接决定 L1（即使分数不为 1）")
    void system1ForcesL1() {
        // greeting + 长描述：分数可能 >1 但 System 1 强制 L1
        var result = router.route("你好".repeat(200), intent("greeting", 3, false));
        assertThat(result.getSystemChoice().isSystem1()).isTrue();
        assertThat(result.getLevel()).isEqualTo("L1");
    }
}
