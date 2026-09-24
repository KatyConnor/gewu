package com.gewu.application.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 套餐 DTO（含窗口项）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaPlanDTO {

    private String id;
    private String planName;
    private String description;
    private Integer status;
    private List<QuotaWindowItemDTO> items;
}
