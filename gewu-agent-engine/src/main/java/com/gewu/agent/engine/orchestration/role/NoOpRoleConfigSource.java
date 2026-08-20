package com.gewu.agent.engine.orchestration.role;

import java.util.List;

/**
 * {@link RoleConfigSource} 的 NoOp 默认实现 - 不提供外部角色配置。
 *
 * @since 1.0.0
 */
public class NoOpRoleConfigSource implements RoleConfigSource {

    @Override
    public List<AgentRoleSpec> loadRoles() {
        return List.of();
    }
}