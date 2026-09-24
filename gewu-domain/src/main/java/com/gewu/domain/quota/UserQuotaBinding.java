package com.gewu.domain.quota;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户套餐绑定：一用户同时只有一个生效绑定（user_id 唯一键）。
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_quota_binding")
public class UserQuotaBinding extends BaseEntity {

    /** 用户 ID */
    private String userId;

    /** 套餐 ID */
    private String planId;

    /** 状态：1=生效 2=停用 */
    private Integer status;
}
