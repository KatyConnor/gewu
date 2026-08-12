# 格物平台上线后监控方案

> 生产环境上线后持续监控计划

## 1. 监控目标

### 1.1 服务可用性

- SLA 目标: 99.9%
- 最大停机时间: 8.76 小时/年
- 故障响应时间: < 15 分钟

### 1.2 性能指标

| 指标 | 目标 | 告警阈值 |
|------|------|----------|
| HTTP P95 延迟 | < 200ms | > 500ms |
| HTTP P99 延迟 | < 500ms | > 1000ms |
| 错误率 | < 0.1% | > 1% |
| 吞吐量 | > 1000 QPS | < 500 QPS |

### 1.3 资源使用

| 资源 | 目标 | 告警阈值 |
|------|------|----------|
| CPU 使用率 | < 70% | > 85% |
| 内存使用率 | < 80% | > 90% |
| 磁盘使用率 | < 80% | > 90% |
| 网络带宽 | < 80% | > 90% |

## 2. 监控指标

### 2.1 应用指标

```promql
# 请求速率
sum(rate(http_server_requests_seconds_count[5m]))

# P95 延迟
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))

# 错误率
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))
```

### 2.2 JVM 指标

```promql
# 堆内存使用
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# GC 暂停时间
rate(jvm_gc_pause_seconds_sum[5m])

# 线程数
jvm_threads_live_threads
```

### 2.3 数据库指标

```promql
# 连接数
hikaricp_connections_active

# 慢查询
rate(mysql_global_status_slow_queries[5m])

# 查询延迟
rate(mysql_global_status_seconds_sql[5m])
```

### 2.4 缓存指标

```promql
# 缓存命中率
redis_keyspace_hits_total / (redis_keyspace_hits_total + redis_keyspace_misses_total)

# 内存使用
redis_memory_used_bytes / redis_memory_max_bytes
```

## 3. 告警配置

### 3.1 Critical 告警

| 告警 | 条件 | 通知方式 |
|------|------|----------|
| 服务不可用 | 健康检查失败 | 电话 + 短信 + 邮件 |
| 数据库宕机 | 连接失败 | 电话 + 短信 + 邮件 |
| 数据丢失 | 数据不一致 | 电话 + 短信 + 邮件 |

### 3.2 Warning 告警

| 告警 | 条件 | 通知方式 |
|------|------|----------|
| 高延迟 | P95 > 500ms | 邮件 + 钉钉 |
| 高错误率 | > 1% | 邮件 + 钉钉 |
| 资源不足 | > 85% | 邮件 + 钉钉 |

### 3.3 Info 告警

| 告警 | 条件 | 通知方式 |
|------|------|----------|
| 发布成功 | 部署完成 | 钉钉 |
| 扩容完成 | HPA 触发 | 钉钉 |
| 备份完成 | 备份成功 | 邮件 |

## 4. 监控工具

### 4.1 Prometheus

- 端口: 9090
- 数据保留: 30 天
- 采集间隔: 15s

### 4.2 Grafana

- 端口: 3001
- 仪表盘: 格物平台生产监控
- 数据源: Prometheus

### 4.3 Alertmanager

- 端口: 9093
- 通知渠道: Slack + PagerDuty

## 5. 监控仪表盘

### 5.1 核心仪表盘

- **概览面板**: QPS、延迟、错误率
- **JVM 面板**: 内存、GC、线程
- **数据库面板**: 连接池、慢查询
- **缓存面板**: 命中率、内存

### 5.2 业务仪表盘

- **用户指标**: 注册、登录、活跃
- **会话指标**: 创建、消息、分享
- **Agent 指标**: 配置、执行、成功率

## 6. 日志监控

### 6.1 日志级别

- ERROR: 立即告警
- WARN: 汇总告警
- INFO: 正常记录
- DEBUG: 调试使用

### 6.2 日志查询

```logql
# 查询错误日志
{job="gewu-platform"} |= "ERROR"

# 查询慢查询
{job="gewu-platform"} |~ "slow-sql"

# 查询特定接口
{job="gewu-platform"} | json | uri="/api/v1/sessions"
```

## 7. 监控响应

### 7.1 响应流程

1. 告警触发
2. 通知相关人员
3. 初步诊断
4. 采取措施
5. 恢复服务
6. 复盘改进

### 7.2 响应时间

| 级别 | 响应时间 | 处理时间 |
|------|----------|----------|
| Critical | 15 分钟 | 2 小时 |
| Warning | 30 分钟 | 4 小时 |
| Info | 2 小时 | 24 小时 |

## 8. 监控报告

### 8.1 日报

- 服务可用性
- 关键指标趋势
- 异常事件汇总

### 8.2 周报

- 性能趋势分析
- 容量使用情况
- 优化建议

### 8.3 月报

- SLA 达成情况
- 容量规划建议
- 成本优化建议

## 9. 持续优化

### 9.1 性能优化

- 根据监控数据调优
- 优化慢查询
- 调整连接池配置

### 9.2 容量规划

- 预测资源需求
- 提前扩容
- 成本优化

### 9.3 监控优化

- 调整告警阈值
- 优化监控规则
- 完善监控覆盖
