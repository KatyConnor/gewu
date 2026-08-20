package com.gewu.application.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.spi.PolicyService;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.governance.GovernancePolicyEntity;
import com.gewu.infrastructure.mapper.GovernancePolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 治理策略 DB 适配器 - PolicyService SPI 的落地实现。
 * <p>替换 AgentEngineAutoConfiguration 中的 stub Bean，为引擎提供
 * 按场景查询活跃策略、校验动作、版本回滚能力；同时作为应用服务
 * 承载策略 CRUD 与灰度激活（供 PolicyController 调用）。
 * 活跃策略按场景缓存，写操作后即时失效。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbPolicyServiceAdapter implements PolicyService {

    private final GovernancePolicyMapper policyMapper;
    private final ObjectMapper objectMapper;

    /** 场景 -> 活跃策略缓存（写操作后失效重建） */
    private final Map<String, GovernancePolicyEntity> activeCache = new ConcurrentHashMap<>();

    // ==================== PolicyService SPI ====================

    @Override
    public Map<String, Object> getActivePolicy(String scenario) {
        GovernancePolicyEntity entity = loadActive(scenario);
        if (entity == null) {
            return Map.of();
        }
        return parseRule(entity);
    }

    /**
     * 校验动作是否符合策略：rule_json 的 allow/deny 语义。
     * <ul>
     *   <li>deny 列表包含动作 -> 拒绝</li>
     *   <li>allow 列表非空且不包含动作 -> 拒绝</li>
     *   <li>其余情况（无策略/无列表约束）-> 允许</li>
     * </ul>
     */
    @Override
    public boolean checkPolicy(String scenario, String action) {
        if (action == null || action.isBlank()) {
            return true;
        }
        Map<String, Object> rule = getActivePolicy(scenario);
        if (rule.isEmpty()) {
            return true;
        }
        List<String> deny = stringList(rule.get("deny"));
        if (deny.contains(action)) {
            return false;
        }
        List<String> allow = stringList(rule.get("allow"));
        return allow.isEmpty() || allow.contains(action);
    }

    @Override
    public boolean rollbackPolicy(String scenario) {
        List<GovernancePolicyEntity> versions = listVersions(scenario);
        if (versions.size() < 2) {
            log.warn("策略回滚失败：场景无历史版本: scenario={}", scenario);
            return false;
        }
        // versions 按版本号降序：[0]=当前激活，[1]=上一版本
        GovernancePolicyEntity target = versions.get(1);
        activate(target.getId(), "system");
        log.info("策略回滚: scenario={} -> version={}", scenario, target.getVersion());
        return true;
    }

    // ==================== 应用服务（CRUD/激活） ====================

    /**
     * 创建策略新版本（同场景版本号递增，默认不激活）。
     */
    public GovernancePolicyEntity createPolicy(String scenario, String policyName,
                                                String ruleJson, String description, String userId) {
        Integer maxVersion = policyMapper.selectList(new LambdaQueryWrapper<GovernancePolicyEntity>()
                        .eq(GovernancePolicyEntity::getScenario, scenario))
                .stream().mapToInt(GovernancePolicyEntity::getVersion).max().orElse(0);

        GovernancePolicyEntity entity = new GovernancePolicyEntity();
        entity.setId(Ulid.next());
        entity.setScenario(scenario);
        entity.setPolicyName(policyName);
        entity.setRuleJson(ruleJson);
        entity.setVersion(maxVersion + 1);
        entity.setActive(0);
        entity.setDescription(description);
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        policyMapper.insert(entity);
        log.info("创建策略: scenario={}, version={}, name={}", scenario, entity.getVersion(), policyName);
        return entity;
    }

    /**
     * 查询场景的策略版本列表（版本号降序）。
     */
    public List<GovernancePolicyEntity> listVersions(String scenario) {
        return policyMapper.selectList(new LambdaQueryWrapper<GovernancePolicyEntity>()
                .eq(GovernancePolicyEntity::getScenario, scenario)
                .orderByDesc(GovernancePolicyEntity::getVersion));
    }

    /**
     * 激活指定版本（同场景互斥激活），并失效缓存。
     */
    public void activate(String policyId, String userId) {
        GovernancePolicyEntity target = policyMapper.selectById(policyId);
        if (target == null) {
            throw new IllegalArgumentException("策略不存在: " + policyId);
        }
        policyMapper.selectList(new LambdaQueryWrapper<GovernancePolicyEntity>()
                        .eq(GovernancePolicyEntity::getScenario, target.getScenario())
                        .eq(GovernancePolicyEntity::getActive, 1))
                .forEach(active -> {
                    active.setActive(0);
                    active.setUpdatedBy(userId);
                    policyMapper.updateById(active);
                });
        target.setActive(1);
        target.setUpdatedBy(userId);
        policyMapper.updateById(target);
        activeCache.remove(target.getScenario());
        log.info("激活策略: scenario={}, version={}, policyId={}", target.getScenario(), target.getVersion(), policyId);
    }

    // ==================== 内部方法 ====================

    private GovernancePolicyEntity loadActive(String scenario) {
        return activeCache.computeIfAbsent(scenario, s -> policyMapper.selectOne(
                new LambdaQueryWrapper<GovernancePolicyEntity>()
                        .eq(GovernancePolicyEntity::getScenario, s)
                        .eq(GovernancePolicyEntity::getActive, 1)
                        .orderByDesc(GovernancePolicyEntity::getVersion)
                        .last("LIMIT 1")));
    }

    private Map<String, Object> parseRule(GovernancePolicyEntity entity) {
        try {
            return objectMapper.readValue(entity.getRuleJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("策略规则解析失败: scenario={}, version={}", entity.getScenario(), entity.getVersion(), e);
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
