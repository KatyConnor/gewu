package com.gewu.infrastructure.ratelimit;

import com.gewu.common.annotation.RateLimit;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面 — 基于 Redis 滑动计数窗口的接口限流.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String KEY_PREFIX = "gewu:rate:";

    private final StringRedisTemplate redisTemplate;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String resolvedKey = resolveKey(rateLimit, joinPoint);
        String redisKey = KEY_PREFIX + resolvedKey;

        Long current = redisTemplate.opsForValue().increment(redisKey);
        if (current != null && current == 1L) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(rateLimit.window()));
        }
        if (current != null && current > rateLimit.limit()) {
            log.warn("限流触发: key={}, current={}, limit={}", resolvedKey, current, rateLimit.limit());
            throw BusinessException.of(ResultCode.RATE_LIMITED);
        }
        return joinPoint.proceed();
    }

    private String resolveKey(RateLimit rateLimit, ProceedingJoinPoint joinPoint) {
        String keyExpr = rateLimit.key();
        if (keyExpr == null || keyExpr.isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            return signature.getDeclaringType().getSimpleName() + ":" + signature.getName();
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

            Expression expression = parser.parseExpression(keyExpr);
            Object value = expression.getValue(context);
            return keyExpr + ":" + (value != null ? value : "null");
        } catch (Exception e) {
            log.warn("解析限流键失败: key={}", keyExpr, e);
            return keyExpr;
        }
    }
}