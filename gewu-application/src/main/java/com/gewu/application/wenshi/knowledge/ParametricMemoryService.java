package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.UserProfile;
import com.gewu.infrastructure.mapper.wenshi.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数记忆服务 — 管理用户偏好设置与行为学习产生的参数化知识。
 * <p>
 * 参数记忆用于存储"用户是谁、喜欢什么"的信息，包括：
 * <ul>
 *   <li>手动设置的偏好（source = MANUAL）</li>
 *   <li>从行为中自动学习到的偏好（source = BEHAVIOR_LEARNED）</li>
 * </ul>
 * 支持按 key 精确查询和按用户全量获取两种模式，写入时自动处理 upsert 逻辑。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParametricMemoryService {

    private final UserProfileMapper mapper;

    /**
     * 设置用户的偏好参数。
     * <p>
     * 若该 key 已存在则更新值，否则新建记录。来源标记为 MANUAL。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param key      偏好键名
     * @param value    偏好值（调用方 toString 存储）
     * @since 1.0.0
     */
    public void setPreference(String tenantId, String userId, String key, Object value) {
        UserProfile existing = findByKey(tenantId, userId, key);
        if (existing != null) {
            existing.setProfileValue(value.toString());
            existing.setSource("MANUAL");
            mapper.updateById(existing);
        } else {
            UserProfile profile = new UserProfile();
            profile.setId(com.gewu.common.ulid.Ulid.next());
            profile.setTenantId(tenantId);
            profile.setUserId(userId);
            profile.setProfileKey(key);
            profile.setProfileValue(value.toString());
            profile.setSource("MANUAL");
            mapper.insert(profile);
        }
        log.debug("ParametricMemoryService.setPreference: userId={}, key={}", userId, key);
    }

    /**
     * 获取用户的指定偏好值。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param key      偏好键名
     * @return 偏好值字符串，未找到返回 null
     * @since 1.0.0
     */
    public String getPreference(String tenantId, String userId, String key) {
        UserProfile profile = findByKey(tenantId, userId, key);
        return profile != null ? profile.getProfileValue() : null;
    }

    /**
     * 获取用户的全量偏好配置。
     * <p>
     * 返回该用户下所有 profileKey → profileValue 的映射。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @return 偏好键值对映射，无记录时返回空 Map
     * @since 1.0.0
     */
    public Map<String, String> getProfile(String tenantId, String userId) {
        List<UserProfile> profiles = mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getTenantId, tenantId)
                        .eq(UserProfile::getUserId, userId)
        );
        Map<String, String> result = new HashMap<>();
        for (UserProfile profile : profiles) {
            result.put(profile.getProfileKey(), profile.getProfileValue());
        }
        return result;
    }

    /**
     * 从用户行为中学习并更新偏好参数。
     * <p>
     * 与 {@link #setPreference} 逻辑类似，但来源标记为 BEHAVIOR_LEARNED，
     * 用于区分用户主动设置和系统自动推断的偏好。
     *
     * @param tenantId     租户 ID
     * @param userId       用户 ID
     * @param behaviorKey  行为键名
     * @param learnedValue 学习到的值
     * @since 1.0.0
     */
    public void learnFromBehavior(String tenantId, String userId, String behaviorKey, Object learnedValue) {
        UserProfile existing = findByKey(tenantId, userId, behaviorKey);
        if (existing != null) {
            existing.setProfileValue(learnedValue.toString());
            existing.setSource("BEHAVIOR_LEARNED");
            mapper.updateById(existing);
        } else {
            UserProfile profile = new UserProfile();
            profile.setId(com.gewu.common.ulid.Ulid.next());
            profile.setTenantId(tenantId);
            profile.setUserId(userId);
            profile.setProfileKey(behaviorKey);
            profile.setProfileValue(learnedValue.toString());
            profile.setSource("BEHAVIOR_LEARNED");
            mapper.insert(profile);
        }
        log.debug("ParametricMemoryService.learnFromBehavior: userId={}, key={}", userId, behaviorKey);
    }

    /**
     * 按租户、用户、键名精确查找用户画像记录。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param key      偏好键名
     * @return 匹配的 {@link UserProfile}，未找到返回 null
     */
    private UserProfile findByKey(String tenantId, String userId, String key) {
        return mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getTenantId, tenantId)
                        .eq(UserProfile::getUserId, userId)
                        .eq(UserProfile::getProfileKey, key)
        );
    }
}
