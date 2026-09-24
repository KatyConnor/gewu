package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型配置 DTO — 返回给前端的模型信息。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigDTO {

    private String id;
    private String providerId;
    private String providerCode;
    private String providerName;
    private String modelName;
    private String modelId;
    private String modelParams;
    private String description;
    private Integer status;
    /** 输入单价（元 / 每计价单位 token） */
    private BigDecimal pricePer1kInput;
    /** 输出单价（元 / 每计价单位 token） */
    private BigDecimal pricePer1kOutput;
    /** 计价基准 token 数（价格列的计价单位，默认 1000） */
    private Integer priceUnitTokens;
    /** 上下文输入窗口（tokens） */
    private Integer contextWindowInput;
    /** 最大输出 tokens */
    private Integer contextWindowOutput;
}