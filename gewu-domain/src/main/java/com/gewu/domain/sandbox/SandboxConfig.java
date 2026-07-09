package com.gewu.domain.sandbox;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sandbox_config")
public class SandboxConfig extends BaseEntity {

    private String runtime;
    private String image;
    private Integer cpuLimit;
    private Integer memoryLimitMb;
    private Integer diskLimitMb;
    private Integer networkEnabled;
    private String seccompProfile;
    private String apparmorProfile;
    private Integer readOnlyFs;
    private String allowedMounts;
    private String envVars;
    private Integer timeoutSeconds;
    private Integer status;
}