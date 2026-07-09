package com.gewu.sandbox.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class CreateSandboxCommand {

    private String image;

    @Min(1)
    @Max(8)
    private Integer cpuLimit;

    @Min(128)
    @Max(16384)
    private Integer memoryLimit;

    @Min(256)
    @Max(51200)
    private Integer diskLimit;

    private Boolean networkEnabled;

    @Min(60)
    @Max(86400)
    private Integer timeout;
}
