package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.spi.PermissionResult;
import com.gewu.agent.engine.spi.PermissionService;

/**
 * {@link PermissionService} 的 NoOp 默认实现 - 全部允许。
 *
 * @since 1.0.0
 */
public class NoOpPermissionService implements PermissionService {

    @Override
    public PermissionResult evaluate(String agentId, String toolName, String resource) {
        return PermissionResult.allow();
    }
}
