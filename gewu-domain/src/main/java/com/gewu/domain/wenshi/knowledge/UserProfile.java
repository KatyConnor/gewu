package com.gewu.domain.wenshi.knowledge;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wenshi_user_profile")
public class UserProfile extends BaseEntity {

    private String tenantId;
    private String userId;
    private String profileKey;
    private String profileValue;
    private String source;
}
