package com.gewu.domain.usage;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用量流水账本：一次执行（同步或流式）一条记账，窗口消耗、按模型/时间统计均由本表聚合。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usage_ledger")
public class UsageLedger extends BaseEntity {

    /** 用户 ID */
    private String userId;

    /** 会话 ID */
    private String sessionId;

    /** 模型标识 */
    private String modelId;

    /** 供应商标识 */
    private String provider;

    /** 输入 tokens */
    private Integer inputTokens;

    /** 输出 tokens */
    private Integer outputTokens;

    /** 推理 tokens */
    private Integer reasoningTokens;

    /** 总 tokens */
    private Integer totalTokens;

    /** 成本（元，按模型计价单位换算） */
    private java.math.BigDecimal cost;
}
