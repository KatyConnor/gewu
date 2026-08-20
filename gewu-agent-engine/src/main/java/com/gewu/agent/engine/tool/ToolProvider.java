package com.gewu.agent.engine.tool;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具提供者注解 - 标记 {@link Tool} 实现类为 Spring 组件并自动注册。
 * <p>框架启动时扫描所有 {@code @ToolProvider} 类，注册到 {@link ToolRegistry}。
 *
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface ToolProvider {

    @AliasFor(annotation = Component.class)
    String value() default "";

    /** 工具分类：GIT / CI_CD / DB / DOC / SEARCH / CODE / OPS / CUSTOM */
    String category() default "CUSTOM";
}
