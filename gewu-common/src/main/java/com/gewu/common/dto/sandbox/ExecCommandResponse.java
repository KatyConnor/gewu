package com.gewu.common.dto.sandbox;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExecCommandResponse {

    private Integer exitCode;
    private String stdout;
    private String stderr;
    private Long duration;
}
