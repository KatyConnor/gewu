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
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Veloflow 流程引擎自动装配（宿主引入依赖即用）。
 * <p>装配内容：引擎组件（调度器/注册表/校验器/定时器）+ Mapper + 可选 REST。
 * <p>宿主集成三步：①引入依赖 ②执行 db/init/veloflow_init.sql ③（可选）
 * 实现 {@link FlowIdentityProvider} 桥接登录态、配置 veloflow.rest.enabled=true 启用 REST。
 */
@AutoConfiguration
@ComponentScan("com.veloflow.engine")
@MapperScan("com.veloflow.engine.persistence.mapper")
@EnableScheduling
public class VeloflowAutoConfiguration {

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
