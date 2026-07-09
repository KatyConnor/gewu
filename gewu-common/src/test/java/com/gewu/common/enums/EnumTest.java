package com.gewu.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumTest {

    @Test
    @DisplayName("UserStatus.fromCode 正确映射所有值")
    void userStatusFromCodeMapsAllValues() {
        assertThat(UserStatus.fromCode(1)).isEqualTo(UserStatus.ENABLED);
        assertThat(UserStatus.fromCode(2)).isEqualTo(UserStatus.DISABLED);
        assertThat(UserStatus.fromCode(3)).isEqualTo(UserStatus.LOCKED);
    }

    @Test
    @DisplayName("UserStatus.fromCode 对非法 code 抛出 IllegalArgumentException")
    void userStatusFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> UserStatus.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知用户状态");
    }

    @Test
    @DisplayName("UserStatus getCode 和 getDescription 返回正确值")
    void userStatusGetCodeAndGetDescription() {
        assertThat(UserStatus.ENABLED.getCode()).isEqualTo(1);
        assertThat(UserStatus.ENABLED.getDescription()).isEqualTo("启用");
        assertThat(UserStatus.LOCKED.getDescription()).isEqualTo("锁定");
    }

    @Test
    @DisplayName("SessionType.fromCode 正确映射所有值")
    void sessionTypeFromCodeMapsAllValues() {
        assertThat(SessionType.fromCode(1)).isEqualTo(SessionType.GROUP);
        assertThat(SessionType.fromCode(2)).isEqualTo(SessionType.PRIVATE);
        assertThat(SessionType.fromCode(3)).isEqualTo(SessionType.AI_ASSIST);
    }

    @Test
    @DisplayName("SessionType.fromCode 对非法 code 抛出 IllegalArgumentException")
    void sessionTypeFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> SessionType.fromCode(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知会话类型");
    }

    @Test
    @DisplayName("SessionStatus.fromCode 正确映射所有值")
    void sessionStatusFromCodeMapsAllValues() {
        assertThat(SessionStatus.fromCode(1)).isEqualTo(SessionStatus.ACTIVE);
        assertThat(SessionStatus.fromCode(2)).isEqualTo(SessionStatus.ARCHIVED);
        assertThat(SessionStatus.fromCode(3)).isEqualTo(SessionStatus.CLOSED);
    }

    @Test
    @DisplayName("SessionStatus.fromCode 对非法 code 抛出 IllegalArgumentException")
    void sessionStatusFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> SessionStatus.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知会话状态");
    }

    @Test
    @DisplayName("WorkflowStatus.fromCode 正确映射所有值")
    void workflowStatusFromCodeMapsAllValues() {
        assertThat(WorkflowStatus.fromCode("running")).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(WorkflowStatus.fromCode("completed")).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(WorkflowStatus.fromCode("failed")).isEqualTo(WorkflowStatus.FAILED);
        assertThat(WorkflowStatus.fromCode("suspended")).isEqualTo(WorkflowStatus.SUSPENDED);
        assertThat(WorkflowStatus.fromCode("terminated")).isEqualTo(WorkflowStatus.TERMINATED);
    }

    @Test
    @DisplayName("WorkflowStatus.fromCode 对非法 code 抛出 IllegalArgumentException")
    void workflowStatusFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> WorkflowStatus.fromCode("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知工作流状态");
    }

    @Test
    @DisplayName("MessageType.fromCode 正确映射所有值")
    void messageTypeFromCodeMapsAllValues() {
        assertThat(MessageType.fromCode("text")).isEqualTo(MessageType.TEXT);
        assertThat(MessageType.fromCode("code")).isEqualTo(MessageType.CODE);
        assertThat(MessageType.fromCode("image")).isEqualTo(MessageType.IMAGE);
        assertThat(MessageType.fromCode("file")).isEqualTo(MessageType.FILE);
        assertThat(MessageType.fromCode("system")).isEqualTo(MessageType.SYSTEM);
        assertThat(MessageType.fromCode("ai")).isEqualTo(MessageType.AI);
    }

    @Test
    @DisplayName("MessageType.fromCode 对非法 code 抛出 IllegalArgumentException")
    void messageTypeFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> MessageType.fromCode("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知消息类型");
    }

    @Test
    @DisplayName("DeliveryMode.fromCode 正确映射所有值")
    void deliveryModeFromCodeMapsAllValues() {
        assertThat(DeliveryMode.fromCode("steer")).isEqualTo(DeliveryMode.STEER);
        assertThat(DeliveryMode.fromCode("queue")).isEqualTo(DeliveryMode.QUEUE);
        assertThat(DeliveryMode.fromCode("resume")).isEqualTo(DeliveryMode.RESUME);
    }

    @Test
    @DisplayName("DeliveryMode.fromCode 对非法 code 抛出 IllegalArgumentException")
    void deliveryModeFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> DeliveryMode.fromCode("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知交付方式");
    }

    @Test
    @DisplayName("SandboxRuntime.fromCode 正确映射所有值")
    void sandboxRuntimeFromCodeMapsAllValues() {
        assertThat(SandboxRuntime.fromCode("firecracker")).isEqualTo(SandboxRuntime.FIRECRACKER);
        assertThat(SandboxRuntime.fromCode("gvisor")).isEqualTo(SandboxRuntime.GVISOR);
        assertThat(SandboxRuntime.fromCode("isulad")).isEqualTo(SandboxRuntime.ISULAD);
        assertThat(SandboxRuntime.fromCode("docker")).isEqualTo(SandboxRuntime.DOCKER);
        assertThat(SandboxRuntime.fromCode("kata")).isEqualTo(SandboxRuntime.KATA);
    }

    @Test
    @DisplayName("SandboxRuntime.fromCode 对非法 code 抛出 IllegalArgumentException")
    void sandboxRuntimeFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> SandboxRuntime.fromCode("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知沙箱运行时");
    }

    @Test
    @DisplayName("AgentStatus.fromCode 正确映射所有值")
    void agentStatusFromCodeMapsAllValues() {
        assertThat(AgentStatus.fromCode(1)).isEqualTo(AgentStatus.ENABLED);
        assertThat(AgentStatus.fromCode(2)).isEqualTo(AgentStatus.DISABLED);
    }

    @Test
    @DisplayName("AgentStatus.fromCode 对非法 code 抛出 IllegalArgumentException")
    void agentStatusFromCodeThrowsForInvalidCode() {
        assertThatThrownBy(() -> AgentStatus.fromCode(888))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 Agent 状态");
    }
}
