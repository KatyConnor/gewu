package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新供应商请求。providerCode 不可修改（作为唯一标识）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProviderCommand {

    private String providerName;
    private String baseUrl;
    /** API Key，为 null 或空字符串时表示不修改 */
    private String apiKey;
    private String description;
    private String logoLetter;
    private String logoColor;
    private String textColor;
}