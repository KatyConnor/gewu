package com.gewu.sandbox.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SandboxDTO {

    private String id;
    private String status;
    private String image;
    private Integer cpuLimit;
    private Integer memoryLimit;
    private Integer diskLimit;
    private Boolean networkEnabled;
    private Integer timeoutSeconds;
    private String runtime;
    private Long createdAt;
    private String createdBy;
}
