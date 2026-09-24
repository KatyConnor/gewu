package com.gewu.agent.engine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 引擎配置属性。
 * <p>前缀 {@code agent.engine}，使用方可通过 application.yml 覆盖默认值。
 *
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "agent.engine")
public class AgentEngineProperties {

    /** 核心执行引擎配置 */
    private Engine engine = new Engine();

    /** LLM 客户端配置 */
    private Llm llm = new Llm();

    /** 工具执行配置 */
    private Tool tool = new Tool();

    /** 预算控制配置 */
    private Budget budget = new Budget();

    /** Agent 生命周期管理配置 */
    private Lifecycle lifecycle = new Lifecycle();

    /** 目标规划器配置（GoalPlanner SPI 的 LLM 实现） */
    private Planner planner = new Planner();

    @Data
    public static class Engine {
        /** 单次执行最大工具调用轮次（防失控） */
        private int maxToolRounds = 10;
        /** 默认 max_tokens 上限 */
        private int defaultMaxTokens = 8192;
        /** 默认 temperature */
        private double defaultTemperature = 0.7;
        /** 工具并行执行线程池核心数 */
        private int toolExecutorCorePoolSize = 8;
        /** 工具并行执行线程池最大数 */
        private int toolExecutorMaxPoolSize = 16;
        /** 工具执行队列容量 */
        private int toolExecutorQueueCapacity = 100;
        /** 会话历史默认加载条数 */
        private int defaultHistoryLimit = 50;
        /** 轮次/预算超限优雅降级：超限时发一次无 tools 的总结调用，把"任务失败"变为"降级完成" */
        private boolean limitSummaryEnabled = true;
        /** 超限总结调用的 max_tokens 上限（默认 8192，硬上限 65536；与当轮任务级 maxTokens 相互独立） */
        private int limitSummaryMaxTokens = 8192;
        /** 工具调用死循环检测开关（同工具同参数连续重复识别） */
        private boolean loopDetectionEnabled = true;
        /** 死循环告警阈值：连续相同调用达到该次数时注入提示，引导模型改变策略 */
        private int loopWarnThreshold = 3;
        /** 死循环终止阈值：达到该次数时强制终止并总结进度（注意：合法的同参数轮询也会被终止） */
        private int loopStopThreshold = 10;
        /** 上下文压缩触发阈值（估算输入 tokens / 模型上下文输入窗口，0=禁用压缩） */
        private double contextCompactThreshold = 0.8;
        /** 上下文压缩时保留的最近推理轮数 */
        private int contextCompactKeepRounds = 4;
        /** 轮次预算滚动扩容开关（轮次到顶且无死循环迹象时扩容续跑，而非强制总结） */
        private boolean roundsRenewEnabled = true;
        /** 轮次预算滚动扩容最大次数（每次扩 50% 且 ≥10 轮；扩容次数耗尽才强制总结） */
        private int roundsRenewMax = 2;
        /** 子代理派生（spawn_subagents 内置工具）配置 */
        private Subagents subagents = new Subagents();
    }

    @Data
    public static class Subagents {
        /** 是否启用子代理派生工具 */
        private boolean enabled = true;
        /** 单次派生的最大子代理数 */
        private int maxPerSpawn = 5;
        /** 最大嵌套深度（顶层=0；depth >= maxDepth 时不再注册派生工具） */
        private int maxDepth = 2;
        /** 单个子代理执行超时（秒），超时分支以错误文本降级，不拖垮整体 */
        private int timeoutSeconds = 180;
        /** 子代理执行线程池核心数（与工具执行池隔离，防嵌套 join 死锁） */
        private int corePoolSize = 4;
        /** 子代理执行线程池最大数 */
        private int maxPoolSize = 8;
        /** 子代理是否共享父任务会话历史（默认隔离会话，保证分支独立干净上下文） */
        private boolean shareSessionHistory = false;
        /** 单条子任务提示词长度上限（字符） */
        private int maxPromptChars = 8000;
        /** 单个子代理输出聚合前的截断上限（字符） */
        private int maxOutputChars = 10 * 1024;
    }

    @Data
    public static class Llm {
        /** HTTP 连接超时 */
        private Duration connectTimeout = Duration.ofSeconds(30);
        /** 同步请求超时（同时用于流式响应头阶段限时） */
        private Duration requestTimeout = Duration.ofSeconds(120);
        /** 流式空闲看门狗（毫秒）：SSE 流无数据超过该时长即中断，0=禁用（docs/design/47 问题二） */
        private long streamIdleTimeoutMs = 60_000L;
        /** 限流/瞬态错误最大尝试次数（含首次，docs/design/47 问题三），1=不重试 */
        private int retryMaxAttempts = 3;
        /** 重试退避基数（毫秒），指数退避 + 抖动，尊重 Retry-After 头 */
        private long retryBackoffMs = 1000L;
    }

    @Data
    public static class Tool {
        /** HTTP 工具允许访问的主机白名单（逗号分隔），默认空=拒绝全部外部地址 */
        private String allowedHosts = "";
        /** HTTP 工具最大重定向次数 */
        private int maxRedirects = 5;
        /** 工具输出最大字节数 */
        private int maxOutputSize = 10 * 1024;
        /** 默认工具执行超时（秒） */
        private int defaultTimeoutSeconds = 30;
    }

    @Data
    public static class Budget {
        /** Token 预算基线（L2；L1=除以 divisor、L3=乘以 multiplier）。S9 配置化，替代 defaultMaxTokens×10 硬推导 */
        private long tokenBudget = 81_920;
        /** L2（默认等级）时间预算（毫秒） */
        private long timeBudgetMs = 300_000;
        /** L1（轻量任务）token 预算除数 */
        private int l1TokenDivisor = 5;
        /** L1（轻量任务）时间预算（毫秒） */
        private long l1TimeBudgetMs = 30_000;
        /** L1（轻量任务）最大轮次 */
        private int l1MaxRounds = 3;
        /** L3（复杂任务）token 预算倍数 */
        private int l3TokenMultiplier = 3;
        /** L3（复杂任务）时间预算（毫秒倍数） */
        private int l3TimeMultiplier = 4;
        /** L3（复杂任务）轮次倍数 */
        private int l3RoundsMultiplier = 2;
        /** 单次执行金额上限（元）；>0 启用成本维熔断（L1=÷l1TokenDivisor、L3=×l3TokenMultiplier），默认 0=仅记账观测 */
        private double costBudgetYuan = 0;
        /** 预算告警阈值（token/时间利用率最大值 ≥ 此值发 budget_warning） */
        private double alertThreshold = 0.70;
        /** 降级告警阈值（≥ 此值发 DEGRADE 级告警） */
        private double degradeThreshold = 0.90;
    }

    @Data
    public static class Planner {
        /** LLM 规划器子配置 */
        private LlmPlanner llm = new LlmPlanner();
    }

    @Data
    public static class LlmPlanner {
        /** 是否启用 LLM 规划器（默认关闭，启用后覆盖 DefaultGoalPlanner 单步兜底） */
        private boolean enabled = false;
        /** 规划调用使用的 LLM 供应商（须已在 LlmClientRegistry 注册） */
        private String provider;
        /** 规划调用使用的模型 */
        private String model;
        /** 单次分解的最大步骤数 */
        private int maxSteps = 8;
    }

    @Data
    public static class Lifecycle {
        /** Agent 实例心跳超时阈值（毫秒） */
        private long heartbeatTimeoutMs = 60_000;
        /** 全局执行超时（毫秒） */
        private long globalTimeoutMs = 300_000;
    }
}
