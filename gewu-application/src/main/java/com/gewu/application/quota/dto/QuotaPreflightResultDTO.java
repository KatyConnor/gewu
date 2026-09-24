package com.gewu.application.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 配额预检结果：执行入口据此拒绝/注入预算/发出提醒。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaPreflightResultDTO {

    /** 是否绑定生效套餐（未绑定=不限量） */
    private boolean bound;
    private String planName;
    private List<QuotaWindowStatusDTO> windows;
    /** 各窗口最大利用率 */
    private double maxUtilization;
    /** 熔断开关（用户偏好） */
    private boolean blockEnabled;
    /** 熔断开关开 且 任一窗口利用率 ≥100% */
    private boolean shouldBlock;
    /** 提醒阈值（百分比） */
    private int alertThreshold;
    /** 预检时已跨过提醒阈值（需提示，节流由服务内部保证） */
    private boolean alertTriggered;
    /** 剩余可用 tokens（各窗口余量最小值；未绑定/null=不限） */
    private Long remainingTokens;
}
