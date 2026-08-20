package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱代码执行结果。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecResult {

    private String stdout;
    private String stderr;
    private int exitCode;
    private boolean success;
}
