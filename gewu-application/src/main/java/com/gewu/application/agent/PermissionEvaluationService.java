package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.PermissionResult;
import com.gewu.domain.agent.AgentPermission;
import com.gewu.infrastructure.cache.CacheKeys;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.AgentPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 权限评估服务 - 静态优先级 RBAC + 渐进式信任（L0-L3）条件评估。
 * <p>匹配最高优先级权限规则后，若规则配置了 {@code conditionExpr} 则结合
 * {@link AgentStatService} 产出的信任等级 / 成功率评估条件：
 * <ul>
 *   <li>{@code trustLevel >= L2} - 信任等级门控（L0&lt;L1&lt;L2&lt;L3）</li>
 *   <li>{@code successRate >= 0.8} - 成功率门控（0-1）</li>
 *   <li>支持 {@code AND} 组合多条</li>
 * </ul>
 * 条件不满足时降级为 ask（强制人工确认），避免低信任 Agent 自动获得高权限。
 * 无法解析的条件子句默认通过（保持原行为）并告警。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionEvaluationService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** 信任等级门控子句：trustLevel >= L2 / trustLevel > L1 */
    private static final Pattern TRUST_CLAUSE = Pattern.compile(
            "trustLevel\\s*(>=|>)\\s*(L[0-3])", Pattern.CASE_INSENSITIVE);
    /** 成功率门控子句：successRate >= 0.8 */
    private static final Pattern RATE_CLAUSE = Pattern.compile(
            "successRate\\s*(>=|>)\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)", Pattern.CASE_INSENSITIVE);
    /** AND 连接符 */
    private static final Pattern AND_SPLIT = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> TRUST_RANK = Map.of("L0", 0, "L1", 1, "L2", 2, "L3", 3);

    private final AgentPermissionMapper agentPermissionMapper;
    private final CacheService cacheService;
    private final ObjectProvider<AgentStatService> agentStatServiceProvider;

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

        // 渐进式信任：条件不满足时降级为 ask（强制人工确认）
        if (!evaluateCondition(matched.getConditionExpr(), agentId)) {
            log.info("权限条件不满足，降级为人工确认: agentId={}, rule={}, condition={}",
                    agentId, matched.getPermissionCode(), matched.getConditionExpr());
            return new PermissionResult("ask",
                    "信任条件不满足: " + matched.getConditionExpr() + "，需要用户确认", true);
        }

        boolean requireApproval = "ask".equals(matched.getEffect());
        return new PermissionResult(matched.getEffect(),
                "匹配权限规则: " + matched.getPermissionCode(), requireApproval);
    }

    /** 评估条件表达式（AND 组合多条子句；空表达式恒通过） */
    private boolean evaluateCondition(String conditionExpr, String agentId) {
        if (conditionExpr == null || conditionExpr.isBlank()) {
            return true;
        }
        String trustLevel = resolveTrustLevel(agentId);
        double successRate = resolveSuccessRate(agentId);
        for (String clause : AND_SPLIT.split(conditionExpr.trim())) {
            if (!evaluateClause(clause.trim(), trustLevel, successRate)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateClause(String clause, String trustLevel, double successRate) {
        Matcher trust = TRUST_CLAUSE.matcher(clause);
        if (trust.matches()) {
            int actual = TRUST_RANK.getOrDefault(trustLevel, 0);
            int required = TRUST_RANK.getOrDefault(trust.group(2).toUpperCase(), 0);
            return ">".equals(trust.group(1)) ? actual > required : actual >= required;
        }
        Matcher rate = RATE_CLAUSE.matcher(clause);
        if (rate.matches()) {
            double required = Double.parseDouble(rate.group(2));
            return ">".equals(rate.group(1)) ? successRate > required : successRate >= required;
        }
        // 无法解析的子句默认通过（保持兼容），记录告警供规则修正
        log.warn("权限条件子句无法解析，默认通过: {}", clause);
        return true;
    }

    private String resolveTrustLevel(String agentId) {
        AgentStatService statService = agentStatServiceProvider.getIfAvailable();
        if (statService == null || agentId == null || agentId.isBlank()) {
            return "L0";
        }
        try {
            return statService.getTrustLevel(agentId);
        } catch (Exception e) {
            log.debug("信任等级查询失败，按 L0 处理: agentId={}, cause={}", agentId, e.getMessage());
            return "L0";
        }
    }

    private double resolveSuccessRate(String agentId) {
        AgentStatService statService = agentStatServiceProvider.getIfAvailable();
        if (statService == null || agentId == null || agentId.isBlank()) {
            return 0.0;
        }
        try {
            return statService.getSuccessRate(agentId);
        } catch (Exception e) {
            log.debug("成功率查询失败，按 0 处理: agentId={}, cause={}", agentId, e.getMessage());
            return 0.0;
        }
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
