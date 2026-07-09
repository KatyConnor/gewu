package com.gewu.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存结果注解 — 方法执行结果自动写入 Redis 缓存，命中后直接返回.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheResult {

    String cacheName() default "default";

    String key() default "";

    long ttl() default 300000L;
}