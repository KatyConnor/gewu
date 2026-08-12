package com.gewu.infrastructure.interceptor;

import com.gewu.common.annotation.DataPermission;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 数据权限 AOP 切面 - 拦截 @DataPermission 注解方法，将注解配置写入 ThreadLocal.
 * <p>必须在 MyBatis Mapper 调用前设置上下文，DataPermissionInnerInterceptor 才能感知并改写 SQL.
 */
@Slf4j
@Aspect
@Component
public class DataPermissionAspect {

    @Around("@annotation(dataPermission)")
    public Object around(ProceedingJoinPoint joinPoint, DataPermission dataPermission) throws Throwable {
        DataPermissionContext.set(new DataPermissionConfig(
                dataPermission.orgField(),
                dataPermission.orgAlias()));
        try {
            return joinPoint.proceed();
        } finally {
            DataPermissionContext.clear();
        }
    }
}
