package com.gewu.domain.usage;

import lombok.Data;

/**
 * 消息数按「天」聚合行（统计查询结果行；dayIdx = FLOOR(created_at/86400000)）。
 *
 * @since 1.0.0
 */
@Data
public class UsageMessageDayRow {

    private Long dayIdx;
    private Long userMessages;
    private Long agentMessages;
}
