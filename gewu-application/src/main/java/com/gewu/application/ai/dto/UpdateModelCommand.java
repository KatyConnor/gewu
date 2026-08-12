package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新模型请求。modelId 不可修改（作为唯一标识）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateModelCommand {

    private String providerId;
    private String modelName;
    private String modelParams;
    private String description;
}