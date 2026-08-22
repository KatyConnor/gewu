package com.gewu.agent.engine.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HandoffParser} 指令解析测试。
 */
@DisplayName("Handoff 指令解析器")
class HandoffParserTest {

    private final HandoffParser parser = new HandoffParser();

    @Test
    @DisplayName("HANDOFF:agentId 解析目标节点")
    void handoffWithTarget() {
        var decision = parser.parse("分析完成，下一步 HANDOFF:architect-agent 处理");
        assertThat(decision.isHasDirective()).isTrue();
        assertThat(decision.isFinish()).isFalse();
        assertThat(decision.getTargetNodeId()).isEqualTo("architect-agent");
    }

    @Test
    @DisplayName("HANDOFF:agentId|reason 解析目标与理由")
    void handoffWithReason() {
        var decision = parser.parse("HANDOFF:dev-agent|需要编码实现");
        assertThat(decision.getTargetNodeId()).isEqualTo("dev-agent");
        assertThat(decision.getReason()).isEqualTo("需要编码实现");
    }

    @Test
    @DisplayName("FINISH 声明任务结束")
    void finishDirective() {
        var decision = parser.parse("任务已完成 FINISH");
        assertThat(decision.isHasDirective()).isTrue();
        assertThat(decision.isFinish()).isTrue();
        assertThat(decision.getTargetNodeId()).isNull();
    }

    @Test
    @DisplayName("FINISH:reason 携带结束理由")
    void finishWithReason() {
        var decision = parser.parse("FINISH:所有验收通过");
        assertThat(decision.isFinish()).isTrue();
        assertThat(decision.getReason()).isEqualTo("所有验收通过");
    }

    @Test
    @DisplayName("FINISH 优先于 HANDOFF（同时出现时）")
    void finishTakesPrecedence() {
        var decision = parser.parse("HANDOFF:next-agent 然后 FINISH");
        assertThat(decision.isFinish()).isTrue();
    }

    @Test
    @DisplayName("无指令输出按默认路由")
    void noDirectiveDefaultRoute() {
        var decision = parser.parse("这是一段普通回复，没有任何指令");
        assertThat(decision.isHasDirective()).isFalse();
        assertThat(decision.isFinish()).isFalse();
        assertThat(decision.getTargetNodeId()).isNull();
    }

    @Test
    @DisplayName("null 与空输入返回默认路由；大小写不敏感")
    void nullOrCaseInsensitive() {
        assertThat(parser.parse(null).isHasDirective()).isFalse();
        assertThat(parser.parse("  ").isHasDirective()).isFalse();
        assertThat(parser.parse("handoff:lower-agent").getTargetNodeId()).isEqualTo("lower-agent");
        assertThat(parser.parse("finish").isFinish()).isTrue();
    }
}
