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
    }

    @Data
    public static class Llm {
        /** HTTP 连接超时 */
        private Duration connectTimeout = Duration.ofSeconds(30);
        /** 同步请求超时 */
        private Duration requestTimeout = Duration.ofSeconds(120);
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
        /** L3（复杂任务）时间预算倍数 */
        private int l3TimeMultiplier = 4;
        /** L3（复杂任务）轮次倍数 */
        private int l3RoundsMultiplier = 2;
    }

    @Data
    public static class Lifecycle {
        /** Agent 实例心跳超时阈值（毫秒） */
        private long heartbeatTimeoutMs = 60_000;
        /** 全局执行超时（毫秒） */
        private long globalTimeoutMs = 300_000;
    }
}
