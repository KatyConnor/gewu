package com.gewu.domain.sandbox;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sandbox_audit_log")
public class SandboxAuditLog extends BaseSimpleEntity {

    private String sandboxId;
    private String userId;
    private String action;
    private String resource;
    private String details;
    private String ipAddress;
    private String result;
    private String logHash;
    private Long timestamp;
}