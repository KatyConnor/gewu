package com.gewu.application.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 创建模型请求。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateModelCommand {

    @NotBlank(message = "供应商 ID 不能为空")
    private String providerId;

    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    @NotBlank(message = "模型 ID 不能为空")
    private String modelId;

    private String modelParams;
    private String description;
    private Boolean enableImmediately;
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