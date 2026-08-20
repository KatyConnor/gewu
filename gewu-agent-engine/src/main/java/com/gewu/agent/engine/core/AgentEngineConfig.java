package com.gewu.agent.engine.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.ExecutorService;

/**
 * Agent 执行引擎配置。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEngineConfig {

    /** 单次执行最大工具调用轮次 */
    private int maxToolRounds;

    /** 默认 max_tokens 上限 */
    private int defaultMaxTokens;

    /** 默认采样温度 */
    private double defaultTemperature;

    /** 工具并行执行线程池 */
    private ExecutorService toolExecutor;

    /** 会话历史默认加载条数 */
    private int defaultHistoryLimit;
}
