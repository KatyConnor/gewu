package com.gewu.domain.usage;

import lombok.Data;

/**
 * 用量按「模型 × 天」聚合行（统计查询结果行；dayIdx = FLOOR(created_at/86400000)）。
 *
 * @since 1.0.0
 */
@Data
public class UsageLedgerDayRow {

    private String modelId;
    private Long dayIdx;
    private Long inputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private Long totalTokens;
    private java.math.BigDecimal cost;
}
