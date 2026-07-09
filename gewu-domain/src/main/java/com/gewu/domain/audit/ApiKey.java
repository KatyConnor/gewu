package com.gewu.domain.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_key")
public class ApiKey extends BaseEntity {

    private String userId;
    private String keyName;
    private String keyHash;
    private String keyPrefix;
    private String permissions;
    private Long expiresAt;
    private Long lastUsedAt;
    private Integer status;
}