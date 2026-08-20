package com.gewu.agent.engine.orchestration.role;

import java.util.List;

/**
 * 角色配置源 SPI - 从外部来源（数据库 / 配置文件）加载角色定义。
 * <p>使用方实现此接口提供 DB 驱动的角色配置。
 * 框架提供 {@code NoOpRoleConfigSource}（返回空），此时使用内置 12 个默认角色。
 *
 * @since 1.0.0
 */
public interface RoleConfigSource {

    /** 加载全部角色配置 */
    List<AgentRoleSpec> loadRoles();
}