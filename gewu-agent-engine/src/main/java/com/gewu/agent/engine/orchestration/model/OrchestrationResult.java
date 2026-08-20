package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 编排执行结果。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationResult {

    /** 执行实例 ID */
    private String executionId;
    /** 状态：SUCCESS / FAILED / PAUSED / CANCELLED */
    private String status;
    /** 各节点产出（nodeId -> 产出） */
    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();
    /** 最终输出 */
    private String finalOutput;
    /** 错误信息 */
    private String errorMessage;
    /** token 消耗 */
    private long tokenUsed;
    /** 耗时（毫秒） */
    private long durationMs;

    public static OrchestrationResult success(String executionId, String finalOutput) {
        return OrchestrationResult.builder()
                .executionId(executionId)
                .status("SUCCESS")
                .finalOutput(finalOutput)
                .build();
    }

    public static OrchestrationResult failure(String executionId, String error) {
        return OrchestrationResult.builder()
                .executionId(executionId)
                .status("FAILED")
                .errorMessage(error)
                .build();
    }
}
