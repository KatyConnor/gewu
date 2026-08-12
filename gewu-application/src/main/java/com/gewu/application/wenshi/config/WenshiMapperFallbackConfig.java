package com.gewu.application.wenshi.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Wenshi Mapper 回退配置 - 当 wenshi 模块禁用时（如测试环境），将 wenshi Mapper
 * 绑定到主 SqlSessionFactory，确保 Spring 容器能正常注入 wenshi Mapper Bean。
 * <p>
 * 与 {@link WenshiDataSourceConfig} 上的 {@code @MapperScan} 互斥：
 * <ul>
 *   <li>{@code wenshi.enabled=true}（生产）：{@link WenshiDataSourceConfig} 生效，
 *       wenshi Mapper 走 {@code wenshiSqlSessionFactory}（PostgreSQL）</li>
 *   <li>{@code wenshi.enabled=false}（测试）：本配置生效，
 *       wenshi Mapper 走主 {@code sqlSessionFactory}（H2/MySQL）</li>
 * </ul>
 * 两个 {@code @MapperScan} 不会同时生效，避免 Bean 冲突。
 *
 * @since 1.0.0
 */
@Configuration
@MapperScan(basePackages = "com.gewu.infrastructure.mapper.wenshi",
        sqlSessionFactoryRef = "sqlSessionFactory")
@ConditionalOnProperty(prefix = "gewu.wenshi", name = "enabled", havingValue = "false", matchIfMissing = true)
public class WenshiMapperFallbackConfig {
    // 无需额外代码：sqlSessionFactoryRef 指向主 SqlSessionFactory（由 MyBatis-Plus auto-configuration 创建）。
}