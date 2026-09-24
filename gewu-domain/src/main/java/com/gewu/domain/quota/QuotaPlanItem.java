package com.gewu.domain.quota;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 套餐时间窗口配额项：每套餐每个窗口类型一条（窗口消耗由 usage_ledger 按窗口起点聚合）。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quota_plan_item")
public class QuotaPlanItem extends BaseEntity {

    /** 套餐 ID */
    private String planId;

    /** 窗口类型: FIVE_HOUR / WEEK / MONTH / QUARTER */
    private String windowType;

    /** 窗口内 token 总量上限 */
    private Long tokenLimit;
}
