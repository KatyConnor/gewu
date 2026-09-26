package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排图 Webhook 触发配置（WFC-03）- 每图一条。
 * <p>token 明文仅创建/重置时经 API 返回一次，库内只存 SM3 哈希；
 * 匿名触发端点按哈希命中且 enabled=1 且图为 active 才发起执行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_webhook")
public class OrchestrationWebhookEntity extends BaseEntity {

    /** 编排图 ID（唯一） */
    private String graphId;
    /** Webhook token 的 SM3 哈希（明文不落库） */
    private String tokenHash;
    /** 启停开关 */
    private Integer enabled;
}
