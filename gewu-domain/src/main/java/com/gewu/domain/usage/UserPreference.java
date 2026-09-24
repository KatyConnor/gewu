package com.gewu.domain.usage;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户偏好设置：配额提醒阈值与熔断开关（总量由管理员套餐下发，阈值/开关由用户自持）。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_preference")
public class UserPreference extends BaseEntity {

    /** 用户 ID */
    private String userId;

    /** 配额提醒阈值（百分比，跨阈值提醒一次，默认 80） */
    private Integer quotaAlertThreshold;

    /** 配额熔断开关：1=耗尽后拒绝新任务 0=仅提醒（默认 0） */
    private Integer quotaBlockEnabled;
}
