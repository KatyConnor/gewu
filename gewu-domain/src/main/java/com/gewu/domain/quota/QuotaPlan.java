package com.gewu.domain.quota;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户配额套餐：由管理员制定，含多个时间窗口的 token 总量上限（明细见 {@link QuotaPlanItem}）。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quota_plan")
public class QuotaPlan extends BaseEntity {

    /** 套餐名称 */
    private String planName;

    /** 套餐描述 */
    private String description;

    /** 状态：1=启用 2=停用 */
    private Integer status;
}
