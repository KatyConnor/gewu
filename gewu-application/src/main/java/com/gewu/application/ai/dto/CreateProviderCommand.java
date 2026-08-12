package com.gewu.application.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建供应商请求。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProviderCommand {

    @NotBlank(message = "供应商名称不能为空")
    private String providerName;

    @NotBlank(message = "供应商编码不能为空")
    private String providerCode;

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    private String apiKey;
    private String description;
    private String logoLetter;
    private String logoColor;
    private String textColor;
    private Boolean enableImmediately;
}