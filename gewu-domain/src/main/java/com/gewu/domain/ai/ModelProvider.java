package com.gewu.domain.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型供应商实体 — 对应 model_provider 表。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_provider")
public class ModelProvider extends BaseEntity {

    /** 供应商编码 (openai/anthropic/deepseek/qwen 等) */
    private String providerCode;

    /** 供应商显示名称 */
    private String providerName;

    /** API 基础地址 */
    private String baseUrl;

    /** API 密钥（加密存储） */
    private String apiKey;

    /** 供应商描述 */
    private String description;

    /** Logo 首字母 */
    private String logoLetter;

    /** Logo 渐变色 CSS 类 */
    private String logoColor;

    /** 文字色 CSS 类 */
    private String textColor;

    /** 状态：1=启用 2=停用 */
    private Integer status;

    /** 排序 */
    private Integer sortOrder;
}