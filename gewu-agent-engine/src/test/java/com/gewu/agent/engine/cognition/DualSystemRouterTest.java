package com.gewu.agent.engine.cognition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DualSystemRouter} 双系统路由规则测试。
 */
@DisplayName("双系统路由器")
class DualSystemRouterTest {

    private final DualSystemRouter router = new DualSystemRouter();

    @Test
    @DisplayName("简单意图（greeting/faq/chitchat）走 System 1：REACT + 轻量模型")
    void simpleIntentRoutesToSystem1() {
        for (String intent : new String[]{"greeting", "faq", "chitchat", "confirmation"}) {
            var choice = router.route("任意描述", intent, 0, false);
            assertThat(choice.isSystem1()).as("意图 %s 应走 System 1", intent).isTrue();
            assertThat(choice.getRuntimeMode()).isEqualTo("REACT");
            assertThat(choice.getModelTier()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("需要多步执行走 System 2：PLAN_EXECUTE + 强力模型")
    void multiStepRoutesToSystem2() {
        var choice = router.route("查询订单", "task_query", 1, true);
        assertThat(choice.isSystem1()).isFalse();
        assertThat(choice.getRuntimeMode()).isEqualTo("PLAN_EXECUTE");
        assertThat(choice.getModelTier()).isEqualTo(3);
    }

    @Test
    @DisplayName("实体数量超过 10 个走 System 2")
    void manyEntitiesRoutesToSystem2() {
        var choice = router.route("综合这些数据", "analysis", 11, false);
        assertThat(choice.isSystem1()).isFalse();
    }

    @Test
    @DisplayName("复杂描述关键词（架构/设计/重构）走 System 2")
    void complexDescriptionRoutesToSystem2() {
        var choice = router.route("请设计微服务架构并评估重构方案", "unknown", 0, false);
        assertThat(choice.isSystem1()).isFalse();
    }

    @Test
    @DisplayName("短小简单描述走 System 1")
    void shortSimpleDescriptionRoutesToSystem1() {
        var choice = router.route("查询", "task_query", 0, false);
        assertThat(choice.isSystem1()).isTrue();
    }

    @Test
    @DisplayName("空/null 描述按最低复杂度处理")
    void blankDescriptionHandled() {
        assertThat(router.route("", "unknown", 0, false).isSystem1()).isTrue();
        assertThat(router.route(null, "unknown", 0, false).isSystem1()).isTrue();
    }

    @Test
    @DisplayName("简化路由重载：默认 unknown 意图")
    void simplifiedOverload() {
        var choice = router.route("帮我查询天气");
        assertThat(choice).isNotNull();
    }
}
