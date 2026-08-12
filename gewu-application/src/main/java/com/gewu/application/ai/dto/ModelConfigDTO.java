package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}