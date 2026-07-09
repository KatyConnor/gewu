package com.gewu.infrastructure.cache;

import com.gewu.common.annotation.CacheResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 缓存切面 — 拦截 @CacheResult 注解方法，提供 Redis 二级缓存能力.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CacheAspect {

    private final CacheService cacheService;
    private final CacheConfig cacheConfig;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Boolean> voidTypes = new ConcurrentHashMap<>();

    @Around("@annotation(cacheResult)")
    public Object around(ProceedingJoinPoint joinPoint, CacheResult cacheResult) throws Throwable {
        String cacheKey = buildKey(cacheResult, joinPoint);
        if (cacheKey == null) {
            return joinPoint.proceed();
        }

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> returnType = method.getReturnType();
        if (isVoidType(method, returnType)) {
            return joinPoint.proceed();
        }

        Object cached = cacheService.get(cacheKey, returnType);
        if (cached != null) {
            log.debug("缓存命中: cache={}, key={}", cacheResult.cacheName(), cacheKey);
            return cached;
        }

        Object result = joinPoint.proceed();
        if (result != null) {
            long ttl = cacheResult.ttl() > 0 ? cacheResult.ttl() : cacheConfig.ttlFor(cacheResult.cacheName());
            cacheService.set(cacheKey, result, Duration.ofMillis(ttl));
        }
        return result;
    }

    private String buildKey(CacheResult cacheResult, ProceedingJoinPoint joinPoint) {
        String keyExpr = cacheResult.key();
        if (keyExpr == null || keyExpr.isEmpty()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();
            String[] paramNames = discoverer.getParameterNames(method);

            EvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("result", null);

            Expression expression = parser.parseExpression(keyExpr);
            Object value = expression.getValue(context);
            return cacheResult.cacheName() + ":" + (value != null ? value : "null");
        } catch (Exception e) {
            log.warn("解析缓存键失败: key={}, 使用参数哈希兜底", keyExpr, e);
            return cacheResult.cacheName() + ":" + keyHash(joinPoint.getArgs());
        }
    }

    private boolean isVoidType(Method method, Class<?> returnType) {
        String key = method.toGenericString();
        Boolean cached = voidTypes.get(key);
        if (cached != null) {
            return cached;
        }
        boolean isVoid = void.class.equals(returnType) || Void.class.equals(returnType);
        voidTypes.put(key, isVoid);
        return isVoid;
    }

    private String keyHash(Object[] args) {
        if (args == null || args.length == 0) {
            return "noargs";
        }
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            sb.append(arg != null ? arg.hashCode() : 0).append(":");
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}