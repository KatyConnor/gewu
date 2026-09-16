package com.gewu.common.dto.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Data;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecCommandResponse {

    private Integer exitCode;
    private String stdout;
    private String stderr;
    private Long duration;
}
