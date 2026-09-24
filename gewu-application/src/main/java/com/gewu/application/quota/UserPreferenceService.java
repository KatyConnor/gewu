package com.gewu.application.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.usage.UserPreference;
import com.gewu.infrastructure.mapper.UserPreferenceMapper;
import com.gewu.application.quota.dto.UserPreferenceDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户偏好服务：配额提醒阈值与熔断开关（缺省 80% / 仅提醒）。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private static final int DEFAULT_ALERT_THRESHOLD = 80;
    private static final boolean DEFAULT_BLOCK_ENABLED = false;

    private final UserPreferenceMapper userPreferenceMapper;

    /** 查询用户偏好；无记录时返回缺省值（不落库，首次更新时创建）。 */
    public UserPreferenceDTO get(String userId) {
        UserPreference entity = selectByUserId(userId);
        if (entity == null) {
            return UserPreferenceDTO.builder()
                    .quotaAlertThreshold(DEFAULT_ALERT_THRESHOLD)
                    .quotaBlockEnabled(DEFAULT_BLOCK_ENABLED)
                    .build();
        }
        return UserPreferenceDTO.builder()
                .quotaAlertThreshold(entity.getQuotaAlertThreshold() != null
                        ? entity.getQuotaAlertThreshold() : DEFAULT_ALERT_THRESHOLD)
                .quotaBlockEnabled(entity.getQuotaBlockEnabled() != null
                        ? entity.getQuotaBlockEnabled() == 1 : DEFAULT_BLOCK_ENABLED)
                .build();
    }

    /** 更新用户偏好（首次更新时创建记录）。 */
    @Transactional
    public void update(String userId, Integer alertThreshold, Boolean blockEnabled) {
        UserPreference entity = selectByUserId(userId);
        if (entity == null) {
            entity = new UserPreference();
            entity.setId(Ulid.next());
            entity.setUserId(userId);
            entity.setQuotaAlertThreshold(normalizeThreshold(alertThreshold));
            entity.setQuotaBlockEnabled(blockEnabled != null && blockEnabled ? 1 : 0);
            userPreferenceMapper.insert(entity);
            return;
        }
        if (alertThreshold != null) {
            entity.setQuotaAlertThreshold(normalizeThreshold(alertThreshold));
        }
        if (blockEnabled != null) {
            entity.setQuotaBlockEnabled(blockEnabled ? 1 : 0);
        }
        userPreferenceMapper.updateById(entity);
    }

    /** 阈值收敛到 10~95（避免 0/100 的无意义配置）。 */
    private int normalizeThreshold(Integer threshold) {
        int v = threshold != null ? threshold : DEFAULT_ALERT_THRESHOLD;
        return Math.max(10, Math.min(95, v));
    }

    private UserPreference selectByUserId(String userId) {
        return userPreferenceMapper.selectOne(new LambdaQueryWrapper<UserPreference>()
                .eq(UserPreference::getUserId, userId)
                .last("LIMIT 1"));
    }
}
