package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商 DTO — 返回给前端的供应商信息。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderDTO {

    private String id;
    private String providerCode;
    private String providerName;
    private String baseUrl;
    private String description;
    private String logoLetter;
    private String logoColor;
    private String textColor;
    private Integer status;
    private Integer modelsCount;
}