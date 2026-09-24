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

    /** 轮次/预算超限优雅降级：超限时发一次无 tools 的总结调用（降级完成而非裸报错） */
    @Builder.Default
    private boolean limitSummaryEnabled = true;

    /** 超限总结调用的 max_tokens 上限（默认 8192，硬上限 65536；与当轮任务级 maxTokens 相互独立） */
    @Builder.Default
    private int limitSummaryMaxTokens = 8192;

    /** 工具调用死循环检测开关 */
    @Builder.Default
    private boolean loopDetectionEnabled = true;

    /** 死循环告警阈值：连续相同调用达到该次数时注入策略提示 */
    @Builder.Default
    private int loopWarnThreshold = 3;

    /** 死循环终止阈值：达到该次数时强制终止并总结进度 */
    @Builder.Default
    private int loopStopThreshold = 10;

    /** 上下文压缩触发阈值（估算输入 tokens / 模型上下文输入窗口，0=禁用压缩） */
    @Builder.Default
    private double contextCompactThreshold = 0.8;

    /** 上下文压缩时保留的最近推理轮数 */
    @Builder.Default
    private int contextCompactKeepRounds = 4;

    /** 轮次预算滚动扩容开关（轮次到顶且无死循环迹象时扩容续跑，而非强制总结） */
    @Builder.Default
    private boolean roundsRenewEnabled = true;

    /** 轮次预算滚动扩容最大次数（每次扩 50% 且 ≥10 轮；扩容次数耗尽才强制总结） */
    @Builder.Default
    private int roundsRenewMax = 2;

    /** 子代理并行执行线程池（与 toolExecutor 隔离：spawn 内嵌 join，共用池会嵌套占满死锁） */
    private ExecutorService subAgentExecutor;

    /** 子代理派生（spawn_subagents 内置工具）配置 */
    @Builder.Default
    private Subagents subagents = new Subagents();

    /** ask_user 问答挂起超时秒数：超时后以"未在限时内回答"应答降级续跑（前端可配） */
    @Builder.Default
    private int askTimeoutSeconds = 600;

    /**
     * 子代理派生配置：模型经内置 spawn_subagents 工具动态派生多个独立 ReAct 会话并行执行。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subagents {
        /** 是否启用子代理派生工具 */
        @Builder.Default
        private boolean enabled = true;
        /** 单次派生的最大子代理数 */
        @Builder.Default
        private int maxPerSpawn = 5;
        /** 最大嵌套深度（顶层=0；depth >= maxDepth 时不再注册派生工具） */
        @Builder.Default
        private int maxDepth = 2;
        /** 单个子代理执行超时（秒），超时分支以错误文本降级 */
        @Builder.Default
        private int timeoutSeconds = 180;
        /** 子代理是否共享父任务会话历史（默认隔离会话） */
        @Builder.Default
        private boolean shareSessionHistory = false;
        /** 单条子任务提示词长度上限（字符） */
        @Builder.Default
        private int maxPromptChars = 8000;
        /** 单个子代理输出聚合前的截断上限（字符） */
        @Builder.Default
        private int maxOutputChars = 10 * 1024;
    }
}
