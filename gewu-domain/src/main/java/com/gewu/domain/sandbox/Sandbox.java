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
    private String sandboxName;
    private String status;
    private String image;
    private Integer cpuLimit;
    private Integer memoryLimitMb;
    private Integer diskLimitMb;
    private Integer networkEnabled;
    private Integer timeoutSeconds;
    private String runtime;
    private Long startedAt;
    private Long stoppedAt;
    private String ip;
    private String ports;
    private String source;
    private String projectId;
    private String agentId;
    private Integer autoDestroy;
    private Long lastUsedAt;
    private Long expireAt;
    private String workspaceId;
}
