package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.PermissionResult;
import com.gewu.agent.engine.spi.PermissionService;
import com.gewu.application.agent.PermissionEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link PermissionService} 业务适配 - 桥接框架与现有 {@link PermissionEvaluationService}。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbPermissionServiceAdapter implements PermissionService {

    private final PermissionEvaluationService delegate;

    @Override
    public PermissionResult evaluate(String agentId, String toolName, String resource) {
        var result = delegate.evaluate(agentId, toolName, resource);
        return PermissionResult.builder()
                .effect(result.getEffect())
                .reason(result.getReason())
                .requireApproval(result.isRequireApproval())
                .build();
    }
}