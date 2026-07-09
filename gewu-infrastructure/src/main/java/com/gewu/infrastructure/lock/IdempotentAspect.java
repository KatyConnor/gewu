package com.gewu.infrastructure.lock;

import com.gewu.common.annotation.Idempotent;
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
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 幂等性切面 — 基于分布式锁防止接口重复提交.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final long WAIT_TIME_MS = 100L;
    private static final String KEY_PREFIX = "idempotent:";

    private final DistributedLockService distributedLockService;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String lockKey = KEY_PREFIX + resolveKey(idempotent, joinPoint);
        long leaseTime = idempotent.timeout() * 1000L;

        try {
            return distributedLockService.tryLockAndReturn(
                    lockKey, () -> proceedQuietly(joinPoint), WAIT_TIME_MS, leaseTime);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("获取分布式锁失败")) {
                log.warn("幂等校验失败，检出重复提交: key={}", lockKey);
                throw BusinessException.of(ResultCode.PARAM_INVALID, "请勿重复提交");
            }
            throw e;
        }
    }

    private Object proceedQuietly(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
        }
    }

    private String resolveKey(Idempotent idempotent, ProceedingJoinPoint joinPoint) {
        String keyExpr = idempotent.key();
        if (keyExpr == null || keyExpr.isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            return signature.getDeclaringType().getSimpleName() + ":" + signature.getName() + ":" + joinPoint.getArgs().length;
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
            return value != null ? value.toString() : "null";
        } catch (Exception e) {
            log.warn("解析幂等键失败: key={}", keyExpr, e);
            return keyExpr;
        }
    }
}