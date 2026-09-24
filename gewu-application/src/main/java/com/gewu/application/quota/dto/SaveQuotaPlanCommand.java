package com.gewu.application.quota.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建/更新套餐请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveQuotaPlanCommand {

    @NotBlank(message = "套餐名称不能为空")
    private String planName;
    private String description;
    /** 1=启用 2=停用（缺省启用） */
    private Integer status;
    private List<QuotaWindowItemDTO> items;
}
