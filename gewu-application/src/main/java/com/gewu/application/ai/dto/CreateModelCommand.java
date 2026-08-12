package com.gewu.application.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}