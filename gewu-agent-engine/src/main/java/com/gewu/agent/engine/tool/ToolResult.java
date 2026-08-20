package com.gewu.agent.engine.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    /** 是否成功 */
    private boolean success;
    /** 输出内容 */
    private String output;
    /** 错误信息（失败时） */
    private String error;
    /** 耗时（毫秒） */
    private long duration;
    /** 输出是否被截断 */
    private boolean truncated;

    public static ToolResult success(String output, long duration) {
        return ToolResult.builder().success(true).output(output).duration(duration).build();
    }

    public static ToolResult failure(String error, long duration) {
        return ToolResult.builder().success(false).error(error).duration(duration).build();
    }
}
