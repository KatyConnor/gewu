# 性能调优指南

> 格物平台生产环境性能调优方案

## 1. JVM 调优

### 1.1 堆内存配置

根据服务器内存（8GB）配置 JVM 参数：

```bash
# Dockerfile 中的 JAVA_OPTS
JAVA_OPTS="-Xms2g -Xmx2g -Xmn1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+ExplicitGCInvokesConcurrent -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"
```

### 1.2 GC 策略

使用 G1GC，适合大堆内存应用：

```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m
-XX:InitiatingHeapOccupancyPercent=45
-XX:G1ReservePercent=15
```

### 1.3 性能监控

开启 JMX 远程监控：

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

## 2. 数据库连接池优化

### 2.1 HikariCP 配置

```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 10          # 最小空闲连接数
      maximum-pool-size: 50     # 最大连接数
      idle-timeout: 60000       # 空闲连接超时 60s
      max-lifetime: 1800000     # 连接最大生命周期 30min
      connection-timeout: 10000 # 连接超时 10s
      leak-detection-threshold: 30000  # 泄漏检测 30s
      connection-test-query: SELECT 1  # 连接测试查询
```

### 2.2 连接池监控

定期检查连接池状态：

```sql
-- MySQL 活跃连接数
SHOW PROCESSLIST;

-- HikariCP 监控指标
-- hikaricp_connections_active
-- hikaricp_connections_idle
-- hikaricp_connections_pending
-- hikaricp_connections_timeout_total
```

## 3. Redis 缓存优化

### 3.1 Lettuce 连接池

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          min-idle: 8
          max-active: 32
          max-idle: 16
          max-wait: 3000ms
        shutdown-timeout: 200ms
```

### 3.2 缓存策略

```yaml
gewu:
  cache:
    ttl: 300000  # 5 分钟
  performance:
    cache:
      user-ttl: 1800000       # 用户信息 30 分钟
      project-ttl: 1800000    # 项目信息 30 分钟
      session-ttl: 600000     # 会话信息 10 分钟
      agent-ttl: 3600000      # Agent 配置 1 小时
      workflow-ttl: 3600000   # 工作流配置 1 小时
      permission-ttl: 7200000 # 权限信息 2 小时
```

## 4. Tomcat 连接器优化

### 4.1 线程池配置

```yaml
server:
  tomcat:
    max-threads: 200           # 最大工作线程数
    min-spare-threads: 20      # 最小空闲线程数
    accept-count: 100          # 等待队列长度
    connection-timeout: 10000  # 连接超时 10s
    max-connections: 8192      # 最大连接数
    keep-alive-timeout: 30000  # Keep-Alive 超时 30s
```

### 4.2 NIO 连接器

使用 NIO 连接器提高并发性能：

```java
@Bean
public ServletWebServerFactory servletWebServerFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addConnectorCustomizers(connector -> {
        connector.setProperty("protocol", "org.apache.coyote.http11.Http11NioProtocol");
    });
    return factory;
}
```

## 5. SQL 优化

### 5.1 慢 SQL 监控

```yaml
gewu:
  performance:
    slow-sql-threshold-ms: 500  # 慢 SQL 阈值 500ms
```

### 5.2 索引优化

确保常用查询字段有索引：

```sql
-- 用户表索引
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);

-- 会话表索引
CREATE INDEX idx_sessions_user_id ON session(user_id);
CREATE INDEX idx_sessions_created_at ON session(created_at);

-- 消息表索引
CREATE INDEX idx_messages_session_id ON session_message(session_id);
CREATE INDEX idx_messages_created_at ON session_message(created_at);
```

### 5.3 批量操作优化

```yaml
mybatis-plus:
  configuration:
    default-executor-type: batch
```

## 6. 线程池优化

### 6.1 异步任务线程池

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

### 6.2 线程池监控

定期检查线程池状态：

```java
@GetMapping("/actuator/threadpools")
public Map<String, Object> threadPools() {
    Map<String, Object> pools = new HashMap<>();
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;
    pools.put("active", executor.getActiveCount());
    pools.put("poolSize", executor.getPoolSize());
    pools.put("maxPoolSize", executor.getMaxPoolSize());
    pools.put("queueSize", executor.getThreadPoolExecutor().getQueue().size());
    return pools;
}
```

## 7. 网络优化

### 7.1 HTTP 压缩

```yaml
server:
  compression:
    enabled: true
    mime-types: text/html,text/xml,text/plain,text/css,application/json,application/javascript,image/svg+xml
    min-response-size: 1024
```

### 7.2 Keep-Alive

```yaml
server:
  tomcat:
    keep-alive-timeout: 30000
```

## 8. 监控指标

### 8.1 关键指标

| 指标 | 阈值 | 说明 |
|------|------|------|
| JVM 堆内存使用率 | < 85% | 避免频繁 GC |
| GC 暂停时间 | < 200ms | G1GC 目标 |
| HTTP P95 延迟 | < 200ms | 核心 API |
| HTTP P99 延迟 | < 500ms | 所有 API |
| 数据库连接池使用率 | < 80% | 避免连接耗尽 |
| Redis 缓存命中率 | > 90% | 减少 DB 压力 |
| 线程池使用率 | < 80% | 避免线程耗尽 |
| 错误率 | < 0.1% | 服务稳定性 |

### 8.2 Grafana 仪表盘

建议配置以下仪表盘：

1. **JVM 监控**: 堆内存、GC 次数、线程数
2. **HTTP 监控**: 请求速率、延迟分布、错误率
3. **数据库监控**: 连接池、慢查询、SQL 执行时间
4. **缓存监控**: 命中率、内存使用、Key 数量
5. **系统监控**: CPU、内存、磁盘、网络

## 9. 压力测试

### 9.1 测试场景

```bash
# 登录认证 - 100 并发
jmeter -n -t login_test.jmx -l results/login.jtl -e -o results/login

# 会话列表 - 200 并发
jmeter -n -t session_test.jmx -l results/session.jtl -e -o results/session

# AI 对话 - 50 并发
jmeter -n -t ai_chat_test.jmx -l results/ai_chat.jtl -e -o results/ai_chat

# 混合负载 - 750 并发
jmeter -n -t mixed_test.jmx -l results/mixed.jtl -e -o results/mixed
```

### 9.2 验收标准

- [ ] 750 并发下系统稳定运行 30 分钟
- [ ] 核心 API P95 < 200ms
- [ ] 错误率 < 1%
- [ ] 无内存泄漏
- [ ] 数据库连接池无耗尽

## 10. 故障排查

### 10.1 内存溢出

```bash
# 生成堆转储
jmap -dump:live,format=b,file=heapdump.hprof <pid>

# 分析堆转储
jhat heapdump.hprof
```

### 10.2 线程死锁

```bash
# 生成线程转储
jstack <pid> > thread_dump.txt

# 分析死锁
grep -A 20 "BLOCKED" thread_dump.txt
```

### 10.3 CPU 过高

```bash
# 查找高 CPU 线程
top -H -p <pid>

# 转换线程 ID
printf "%x\n" <tid>

# 分析线程堆栈
jstack <pid> | grep -A 30 "<hex_tid>"
```
