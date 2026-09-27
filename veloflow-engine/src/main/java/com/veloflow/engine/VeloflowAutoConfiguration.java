package com.veloflow.engine;

import com.veloflow.engine.identity.DefaultFlowIdentityProvider;
import com.veloflow.engine.identity.FlowIdentityProvider;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import com.veloflow.engine.runtime.WorkflowNodeHandlerRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Veloflow 流程引擎自动装配（宿主引入依赖即用）。
 * <p>装配内容：引擎组件（调度器/注册表/校验器/定时器）+ Mapper + 可选 REST。
 * <p>宿主集成三步：①引入依赖 ②执行 db/init/veloflow_init.sql ③（可选）
 * 实现 {@link FlowIdentityProvider} 桥接登录态、配置 veloflow.rest.enabled=true 启用 REST。
 */
@AutoConfiguration
@ComponentScan("com.veloflow.engine")
@MapperScan(basePackages = "com.veloflow.engine.persistence.mapper",
        annotationClass = org.apache.ibatis.annotations.Mapper.class,
        sqlSessionFactoryRef = "veloflowSqlSessionFactory")
@EnableScheduling
public class VeloflowAutoConfiguration {

    /**
     * 引擎自有 SqlSessionFactory（单数据源宿主默认路径）：
     * 绑定宿主主 DataSource，MyBatis-Plus 全局配置透传。
     * <p>多数据源宿主（如平台 MySQL 主 + PG 问石）应配置
     * {@code veloflow.mapper-scan.enabled=false} 关闭本工厂，
     * 并在主 @MapperScan 中追加本引擎 mapper 包（绑定主 factory）。
     */
    @Bean
    @ConditionalOnProperty(name = "veloflow.mapper-scan.enabled", havingValue = "true", matchIfMissing = true)
    public org.apache.ibatis.session.SqlSessionFactory veloflowSqlSessionFactory(
            javax.sql.DataSource dataSource) throws Exception {
        com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factory =
                new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        return factory.getObject();
    }

    @Bean
    @ConditionalOnMissingBean(FlowIdentityProvider.class)
    public SqlSessionTemplate veloflowSqlSessionTemplate(
            org.apache.ibatis.session.SqlSessionFactory veloflowSqlSessionFactory) {
        return new SqlSessionTemplate(veloflowSqlSessionFactory);
    }

    /** 身份 SPI：宿主未实现时默认 system 透传 */
    @Bean
    @ConditionalOnMissingBean(FlowIdentityProvider.class)
    public FlowIdentityProvider flowIdentityProvider() {
        return new DefaultFlowIdentityProvider();
    }

    /** 节点处理器注册表（收集全部 WorkflowNodeHandler Bean） */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowNodeHandlerRegistry workflowNodeHandlerRegistry(
            ObjectProvider<WorkflowNodeHandler> handlers) {
        return new WorkflowNodeHandlerRegistry(handlers.stream().toList());
    }

    /** 内置 REST API（默认关闭；宿主可 veloflow.rest.enabled=true 启用） */
    @Bean
    @ConditionalOnProperty(name = "veloflow.rest.enabled", havingValue = "true")
    public Object veloflowRestMarker() {
        return new Object();
    }
}
