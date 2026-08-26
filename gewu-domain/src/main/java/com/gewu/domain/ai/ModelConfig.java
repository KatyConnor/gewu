package com.gewu.domain.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 模型配置实体 - 对应 model_config 表。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_config")
public class ModelConfig extends BaseEntity {

    /** 供应商 ID */
    private String providerId;

    /** 模型显示名称 */
    private String modelName;

    /** 模型标识 (如 gpt-4o, qwen-plus) */
    private String modelId;

    /** 模型参数 (JSON: temperature, max_tokens 等) */
    private String modelParams;

    /** 模型描述 */
    private String description;

    /** 状态：1=启用 2=停用 */
    private Integer status;

    /** 输入每 1K token 单价（元），0=不计费（T4.1） */
    private BigDecimal pricePer1kInput;

    /** 输出每 1K token 单价（元），0=不计费（T4.1） */
    private BigDecimal pricePer1kOutput;

    /** 排序 */
    private Integer sortOrder;
}