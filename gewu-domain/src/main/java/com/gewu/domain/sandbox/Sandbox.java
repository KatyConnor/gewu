package com.gewu.domain.sandbox;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sandbox")
public class Sandbox extends BaseEntity {

    private String configId;
    private String containerId;
    private String status;
    private String image;
    private Integer cpuLimit;
    private Integer memoryLimitMb;
    private Integer diskLimitMb;
    private Integer networkEnabled;
    private Integer timeoutSeconds;
    private String runtime;
}
