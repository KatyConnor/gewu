package com.gewu.application.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户偏好 DTO（配额提醒阈值与熔断开关）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceDTO {

    /** 配额提醒阈值（百分比，10~95） */
    private Integer quotaAlertThreshold;

    /** 配额熔断开关：true=耗尽后拒绝新任务 false=仅提醒 */
    private Boolean quotaBlockEnabled;
}
