package com.gewu.application.config;

import com.gewu.common.context.UserContext;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import com.veloflow.engine.identity.FlowIdentityProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Veloflow 身份桥接——把平台登录态（UserContext）与用户目录（UserAccount）
 * 适配为流程引擎的身份 SPI，使引擎的审计/待办/分派展示使用平台用户体系。
 */
@Component
@RequiredArgsConstructor
public class VeloflowIdentityBridge implements FlowIdentityProvider {

    private final UserAccountMapper userAccountMapper;

    @Override
    public String currentUserId() {
        return UserContext.currentUserId();
    }

    @Override
    public String currentUsername() {
        return UserContext.currentUsername();
    }

    @Override
    public java.util.List<String> currentRoles() {
        com.gewu.common.context.UserContext ctx = com.gewu.common.context.UserContext.get();
        return ctx != null && ctx.getRoleCodes() != null ? ctx.getRoleCodes() : java.util.List.of();
    }

    @Override
    public Map<String, String> batchUserNames(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName));
    }
}
