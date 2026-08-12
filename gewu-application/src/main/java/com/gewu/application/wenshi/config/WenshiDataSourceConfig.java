package com.gewu.application.wenshi.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 问石数据源配置 - 为问石模块提供独立的数据库连接池和事务管理。
 * <p>
 * <b>设计说明</b>：为了避免自定义 {@code DataSource} Bean 干扰 Spring Boot 的
 * {@code DataSourceAutoConfiguration.PooledDataSourceConfiguration}（其条件为
 * {@code @ConditionalOnMissingBean(DataSource.class)}），本配置将问石数据源包装在
 * {@link WenshiDataSourceHolder} 中注册。这样 Bean 类型是 {@code WenshiDataSourceHolder}
 * 而非 {@code DataSource}，不会触发主数据源自动配置的跳过逻辑。
 * <p>
 * 数据源隔离：
 * <ul>
 *   <li>主数据源 - 由 Spring Boot 自动配置创建（MySQL），用于业务表</li>
 *   <li>{@code wenshiDataSource} - 问石数据源（PostgreSQL+pgvector），用于向量存储</li>
 * </ul>
 * <p>
 * Mapper 隔离：通过独立的 {@code @MapperScan} 将 {@code com.gewu.infrastructure.mapper.wenshi}
 * 包下的 Mapper 绑定到 {@code wenshiSqlSessionFactory}（PostgreSQL 数据源），
 * 主 {@code @MapperScan} 通过 {@code annotationClass=Mapper.class} 跳过这些无注解接口。
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(WenshiProperties.class)
@EnableTransactionManagement
@ConditionalOnProperty(prefix = "gewu.wenshi", name = "enabled", havingValue = "true")
@MapperScan(
        basePackages = "com.gewu.infrastructure.mapper.wenshi",
        sqlSessionTemplateRef = "wenshiSqlSessionTemplate"
)
public class WenshiDataSourceConfig {

    /**
     * 问石数据源持有者 - 包装 DataSource 以避免干扰 Spring Boot 主数据源自动配置。
     * <p>
     * 如果直接注册 {@code DataSource} 类型的 Bean，Spring Boot 的
     * {@code @ConditionalOnMissingBean(DataSource.class)} 条件将不满足，导致主数据源不被创建。
     */
    @Data
    public static class WenshiDataSourceHolder {
        private final DataSource dataSource;
    }

    /**
     * 创建问石模块独立数据源（包装在 Holder 中）。
     *
     * @param properties 问石配置属性
     * @return 问石数据源持有者
     * @since 1.0.0
     */
    @Bean(name = "wenshiDataSourceHolder")
    public WenshiDataSourceHolder wenshiDataSourceHolder(WenshiProperties properties) {
        WenshiProperties.Datasource ds = properties.getDatasource();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(ds.getUrl());
        dataSource.setUsername(ds.getUsername());
        dataSource.setPassword(ds.getPassword());
        dataSource.setDriverClassName(ds.getDriverClassName());
        dataSource.setMinimumIdle(ds.getMinimumIdle());
        dataSource.setMaximumPoolSize(ds.getMaximumPoolSize());
        dataSource.setPoolName("WenshiHikariPool");
        return new WenshiDataSourceHolder(dataSource);
    }

    /**
     * 创建问石模块事务管理器。
     *
     * @param holder 问石数据源持有者
     * @return 事务管理器实例
     * @since 1.0.0
     */
    @Bean(name = "wenshiTransactionManager")
    public PlatformTransactionManager wenshiTransactionManager(WenshiDataSourceHolder holder) {
        return new DataSourceTransactionManager(holder.getDataSource());
    }

    @Bean(name = "wenshiFlyway", initMethod = "migrate")
    public Flyway wenshiFlyway(WenshiDataSourceHolder holder) {
        return Flyway.configure()
                .dataSource(holder.getDataSource())
                .locations("classpath:db/wenshi")
                .baselineOnMigrate(true)
                .load();
    }

    /**
     * 创建问石模块独立 SqlSessionTemplate - 将 wenshi Mapper 绑定到 PostgreSQL 数据源。
     * <p>
     * 使用 {@link SqlSessionTemplate}（而非直接注册 {@link SqlSessionFactory} Bean）是为了
     * 避免触发 MyBatis-Plus auto-configuration 的 {@code @ConditionalOnMissingBean(SqlSessionFactory.class)}，
     * 否则主 SqlSessionFactory 不会被创建，导致主业务 Mapper 错误地使用了 wenshi 数据源。
     * {@code SqlSessionTemplate} 类型不是 {@code SqlSessionFactory}，不会干扰 auto-configuration。
     * <p>
     * 内部仍通过 {@link MybatisSqlSessionFactoryBean} 创建 SqlSessionFactory，
     * 手动设置全局配置（逻辑删除、驼峰映射），因为不走 Spring Boot 的 auto-configuration。
     *
     * @param holder 问石数据源持有者
     * @return 问石 SqlSessionTemplate 实例
     * @since 1.0.0
     */
    @Bean(name = "wenshiSqlSessionTemplate")
    public SqlSessionTemplate wenshiSqlSessionTemplate(WenshiDataSourceHolder holder,
                                                        MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(holder.getDataSource());

        // MyBatis-Plus 全局配置（与 application.yml 中 mybatis-plus.global-config 保持一致）
        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.INPUT);            // wenshi entity 用 @TableId(type=INPUT) 手动设 ID
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        // 注册 MetaObjectHandler，使 BaseEntity 的 createdAt/deleted/createdBy 等字段自动填充
        // 不注册会导致 NOT NULL 约束违反（created_at BIGINT NOT NULL）
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factory.setGlobalConfig(globalConfig);

        // MyBatis 配置：下划线转驼峰（tenant_id -> tenantId）
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);

        SqlSessionFactory sqlSessionFactory = factory.getObject();
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
