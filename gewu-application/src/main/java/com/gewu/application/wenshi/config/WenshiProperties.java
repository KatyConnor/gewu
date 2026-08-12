package com.gewu.application.wenshi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问石模块配置属性 — 对应配置文件中以 {@code gewu.wenshi} 为前缀的配置项。
 * <p>
 * 包含以下子配置：
 * <ul>
 *   <li>{@link Routing} — 路由策略</li>
 *   <li>{@link Embedding} — 向量嵌入</li>
 *   <li>{@link Vectorstore} — 向量存储</li>
 *   <li>{@link Tenant} — 多租户</li>
 *   <li>{@link Datasource} — 数据源</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "gewu.wenshi")
public class WenshiProperties {

    /** 是否启用问石模块 */
    private boolean enabled = false;

    /** 路由策略配置 */
    private Routing routing = new Routing();
    /** 向量嵌入配置 */
    private Embedding embedding = new Embedding();
    /** 向量存储配置 */
    private Vectorstore vectorstore = new Vectorstore();
    /** 多租户配置 */
    private Tenant tenant = new Tenant();
    /** 数据源配置 */
    private Datasource datasource = new Datasource();

    /**
     * 路由策略配置 — 控制请求路由到不同处理链路。
     *
     * @since 1.0.0
     */
    @Data
    public static class Routing {
        /** 普通聊天路由策略 */
        private String chat = "legacy";
        /** 流式聊天路由策略 */
        private String stream = "legacy";
    }

    /**
     * 向量嵌入配置 — 控制文本向量化策略。
     *
     * @since 1.0.0
     */
    @Data
    public static class Embedding {
        /** 嵌入适配器名称（bge-small / llm-native） */
        private String adapter = "bge-small";
        /** 本地模型文件路径 */
        private String modelPath = "models/bge-small-zh-v1.5.onnx";
        /** 向量维度 */
        private int dimension = 384;
    }

    /**
     * 向量存储配置 — 控制向量持久化策略。
     *
     * @since 1.0.0
     */
    @Data
    public static class Vectorstore {
        /** 向量存储适配器名称（pgvector） */
        private String adapter = "pgvector";
    }

    /**
     * 多租户配置 — 控制租户隔离与审计。
     *
     * @since 1.0.0
     */
    @Data
    public static class Tenant {
        /** 租户隔离级别（row-level / schema-level） */
        private String isolation = "row-level";
        /** 是否开启审计日志 */
        private boolean auditLog = true;
    }

    /**
     * 数据源配置 — 问石模块独立数据库连接池。
     *
     * @since 1.0.0
     */
    @Data
    public static class Datasource {
        /** JDBC 连接地址 */
        private String url = "jdbc:postgresql://localhost:5432/wenshi";
        /** 数据库用户名 */
        private String username = "wenshi";
        /** 数据库密码 */
        private String password;
        /** JDBC 驱动类名 */
        private String driverClassName = "org.postgresql.Driver";
        /** 最小空闲连接数 */
        private int minimumIdle = 2;
        /** 最大连接池大小 */
        private int maximumPoolSize = 10;
    }
}
