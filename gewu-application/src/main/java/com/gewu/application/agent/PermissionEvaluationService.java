package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.PermissionResult;
import com.gewu.domain.agent.AgentPermission;
import com.gewu.infrastructure.cache.CacheKeys;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.AgentPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionEvaluationService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AgentPermissionMapper agentPermissionMapper;
    private final CacheService cacheService;

    public PermissionResult evaluate(String agentId, String toolName, String resource) {
        List<AgentPermission> permissions = loadPermissions(agentId);
        String target = resource != null ? toolName + ":" + resource : toolName;

        AgentPermission matched = null;
        for (AgentPermission perm : permissions) {
            if (matchesPattern(perm.getPermissionCode(), target)) {
                if (matched == null || perm.getPriority() > matched.getPriority()) {
                    matched = perm;
                }
            }
        }

        if (matched == null) {
            return new PermissionResult("ask", "未找到权限规则，需要用户确认", true);
        }

        boolean requireApproval = "ask".equals(matched.getEffect());
        return new PermissionResult(matched.getEffect(),
                "匹配权限规则: " + matched.getPermissionCode(), requireApproval);
    }

    private List<AgentPermission> loadPermissions(String agentId) {
        String cacheKey = CacheKeys.agent(agentId) + ":perms";
        List<AgentPermission> cached = cacheService.get(cacheKey,
                new com.fasterxml.jackson.core.type.TypeReference<List<AgentPermission>>() {});
        if (cached != null) {
            return cached;
        }
        List<AgentPermission> permissions = agentPermissionMapper.selectList(
                new LambdaQueryWrapper<AgentPermission>()
                        .eq(AgentPermission::getAgentId, agentId)
                        .orderByDesc(AgentPermission::getPriority));
        cacheService.set(cacheKey, permissions, CACHE_TTL);
        return permissions;
    }

    private boolean matchesPattern(String pattern, String target) {
        if (pattern == null) return false;
        String regex = Pattern.quote(pattern).replace("\\*", "\\E.*\\Q");
        return target.matches(regex);
    }
}
