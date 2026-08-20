package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 执行记录（由 {@link PersistenceService} SPI 持久化）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRecord {

    private String id;
    private String agentId;
    private String sessionId;
    private String userId;
    /** 状态：running / completed / failed */
    private String status;
    private String input;
    private String output;
    private String errorMessage;
    private Integer tokensUsed;
    private Long startedAt;
    private Long completedAt;
    private Integer durationMs;
}
