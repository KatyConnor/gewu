package com.gewu.application.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 主库 Flyway 迁移配置（S8 稳定性专项）。
 * <p>
 * <b>为什么需要显式 Bean</b>：工程存在 wenshi PG 侧的自定义 {@code wenshiFlyway}
 * Bean（{@code com.gewu.application.wenshi.config.WenshiDataSourceConfig}），其存在使
 * Spring Boot 的 {@code FlywayAutoConfiguration} 因 {@code @ConditionalOnMissingBean(Flyway.class)}
 * <b>整体退避</b>——主库 MySQL 的 V1~V37 迁移在所有部署上从未经 Flyway 执行，
 * schema 全靠旧初始化脚本 + 手工修补（已造成多代漂移，见部署手册 FAQ）。
 * <p>
 * 本配置显式定义主库 Flyway：对既有库按 baseline-version 接管（跳过历史），对
 * 新装库从 V1 全量重放，此后 V39+ 增量迁移自动生效。
 * <p>
 * 测试环境（application-test.yml 设置 flyway.enabled=false）与本 Bean 的
 * {@code @ConditionalOnProperty} 联动跳过。
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayMigrationConfig {

    /**
     * 主库（MySQL）Flyway 实例。
     *
     * @param dataSource   主数据源（Spring Boot 自动配置的 MySQL 连接池）
     * @param baselineVersion 接管版本号（对既有库 baseline 到该版本，跳过历史迁移）
     */
    @Bean(initMethod = "migrate")
    public Flyway mainFlyway(DataSource dataSource,
                             @Value("${spring.flyway.baseline-version:37}") String baselineVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .load();
    }
}
