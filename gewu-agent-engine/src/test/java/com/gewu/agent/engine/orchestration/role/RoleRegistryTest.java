package com.gewu.agent.engine.orchestration.role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoleRegistry} AE-P1-5 守护测试：12 个内置 SDLC 角色的 systemPromptTemplate
 * 必须为非空内容资产（修复前为空壳——14 号提示词清单实证），防止回退为空壳。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@DisplayName("SDLC 角色注册表（AE-P1-5 内容资产守护）")
class RoleRegistryTest {

    @Test
    @DisplayName("12 个内置角色齐备且提示词资产非空（防回退为能力卡空壳）")
    void allRolesHavePromptTemplate() {
        var roles = RoleRegistry.defaultSdlcRoles();

        assertThat(roles).hasSize(12);
        assertThat(roles)
                .extracting(AgentRoleSpec::getRoleCode)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(roles)
                .allSatisfy(spec -> {
                    assertThat(spec.getSystemPromptTemplate())
                            .as("角色 %s 的 systemPromptTemplate 不应为空", spec.getRoleCode())
                            .isNotBlank();
                    assertThat(spec.getSystemPromptTemplate().length())
                            .as("角色 %s 的提示词应有实质内容", spec.getRoleCode())
                            .isGreaterThan(80);
                    assertThat(spec.getCapability()).as("角色 %s 的能力卡", spec.getRoleCode()).isNotNull();
                });
    }
}
