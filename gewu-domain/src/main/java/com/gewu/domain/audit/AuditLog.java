package com.gewu.domain.audit;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_log")
public class AuditLog extends BaseSimpleEntity {

    private String userId;
    private String username;
    private String actionType;
    private String resourceType;
    private String resourceId;
    private String detail;
    private String ipAddress;
    private String userAgent;
    private Integer result;
    private String errorMessage;
    private Integer durationMs;
}