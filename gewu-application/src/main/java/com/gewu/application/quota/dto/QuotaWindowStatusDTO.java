package com.gewu.application.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单窗口配额状态（预检结果项）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaWindowStatusDTO {

    private String windowType;
    private Long tokenLimit;
    private Long usedTokens;
    /** 利用率（0~1+） */
    private double utilization;
}
