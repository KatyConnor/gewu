package com.gewu.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 慢查询监控拦截器 — 记录执行时间超阈值的 SQL.
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    @Value("${gewu.performance.slow-sql-threshold-ms:1000}")
    private long slowSqlThresholdMs;

    private final AtomicLong slowSqlCount = new AtomicLong();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        SqlCommandType commandType = ms.getSqlCommandType();

        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > slowSqlThresholdMs) {
                long count = slowSqlCount.incrementAndGet();
                String sql = extractSql(ms, parameter);
                log.warn("慢SQL [{}ms>{}ms] type={} id={} count={} sql={}",
                        elapsed, slowSqlThresholdMs, commandType, ms.getId(), count, sql);
            } else if (log.isDebugEnabled()) {
                log.debug("SQL [{}ms] type={} id={}", elapsed, commandType, ms.getId());
            }
        }
    }

    private String extractSql(MappedStatement ms, Object parameter) {
        try {
            String raw = ms.getBoundSql(parameter).getSql();
            return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            log.debug("提取 SQL 失败: id={}", ms.getId(), e);
            return "";
        }
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
    }

    public long getSlowSqlCount() {
        return slowSqlCount.get();
    }
}