# 格物平台监控与可观测性方案

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      监控架构                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ 应用服务  │    │ MySQL    │    │ Redis    │              │
│  │ :8080    │    │ :3306    │    │ :6379    │              │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘              │
│       │              │              │                      │
│       ▼              ▼              ▼                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ Actuator │    │ mysqld-  │    │ redis-   │              │
│  │ Prometheus│   │ exporter │    │ exporter │              │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘              │
│       │              │              │                      │
│       └──────────────┼──────────────┘                      │
│                      ▼                                      │
│              ┌──────────────┐                               │
│              │  Prometheus  │                               │
│              │  :9090       │                               │
│              └──────┬───────┘                               │
│                     │                                       │
│         ┌───────────┼───────────┐                          │
│         ▼           ▼           ▼                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                   │
│  │ Grafana  │ │Alertmanager│ │ Loki    │                   │
│  │ :3001    │ │ :9093     │ │ :3100   │                   │
│  └──────────┘ └──────────┘ └──────────┘                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 2. 组件说明

### 2.1 Prometheus (指标采集)

- **端口**: 9090
- **数据保留**: 30 天
- **采集间隔**: 15s
- **配置文件**: `prometheus/prometheus.yml`

### 2.2 Grafana (可视化)

- **端口**: 3001
- **默认账号**: admin / admin123
- **仪表盘**: `grafana/dashboards/gewu-platform.json`

### 2.3 Alertmanager (告警)

- **端口**: 9093
- **通知渠道**: Slack / PagerDuty
- **配置文件**: `alertmanager/config.yml`

### 2.4 Exporters (数据导出)

| Exporter | 端口 | 说明 |
|---------|------|------|
| node-exporter | 9100 | 系统指标 |
| mysqld-exporter | 9104 | MySQL 指标 |
| redis-exporter | 9121 | Redis 指标 |

## 3. 监控指标

### 3.1 应用指标

| 指标 | 类型 | 说明 |
|------|------|------|
| http_server_requests_seconds | Histogram | HTTP 请求延迟 |
| jvm_memory_used_bytes | Gauge | JVM 内存使用 |
| jvm_gc_pause_seconds | Summary | GC 暂停时间 |
| hikaricp_connections_active | Gauge | 数据库连接池 |
| api_rate_limit_exceeded_total | Counter | 限流次数 |

### 3.2 系统指标

| 指标 | 类型 | 说明 |
|------|------|------|
| node_cpu_seconds_total | Counter | CPU 使用 |
| node_memory_MemAvailable_bytes | Gauge | 可用内存 |
| node_filesystem_avail_bytes | Gauge | 可用磁盘 |
| node_network_receive_bytes_total | Counter | 网络流量 |

### 3.3 数据库指标

| 指标 | 类型 | 说明 |
|------|------|------|
| mysql_global_status_threads_connected | Gauge | 连接数 |
| mysql_global_status_slow_queries | Counter | 慢查询 |
| mysql_global_status_queries | Counter | 查询总数 |

### 3.4 缓存指标

| 指标 | 类型 | 说明 |
|------|------|------|
| redis_memory_used_bytes | Gauge | 内存使用 |
| redis_connected_clients | Gauge | 连接数 |
| redis_keyspace_hits_total | Counter | 命中次数 |
| redis_keyspace_misses_total | Counter | 未命中次数 |

## 4. 告警规则

### 4.1 Critical 级别

| 告警 | 条件 | 持续时间 |
|------|------|----------|
| PodFrequentlyRestarting | 重启 > 3 次/5m | 5m |
| DatabaseHealthCheckFailed | 健康检查失败 | 1m |
| LowDiskSpace | 磁盘 < 10% | 5m |

### 4.2 Warning 级别

| 告警 | 条件 | 持续时间 |
|------|------|----------|
| HighP95Latency | P95 > 500ms | 5m |
| HighRateLimitRejection | 限流 > 10% | 10m |
| HighMemoryUsage | 堆内存 > 85% | 5m |

## 5. 部署步骤

### 5.1 启动监控栈

```bash
cd deploy/monitoring
docker compose -f docker-compose.monitoring.yml up -d
```

### 5.2 配置告警通知

编辑 `alertmanager/config.yml`，配置 Slack Webhook 或 PagerDuty：

```yaml
receivers:
  - name: 'slack-notifications'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/xxx/yyy/zzz'
        channel: '#alerts'
```

### 5.3 导入仪表盘

1. 登录 Grafana (http://localhost:3001)
2. 进入 Dashboards → Import
3. 上传 `grafana/dashboards/gewu-platform.json`
4. 选择 Prometheus 数据源

## 6. 日志收集

### 6.1 Loki + Promtail

```yaml
# promtail-config.yml
server:
  http_listen_port: 9080

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: gewu-platform
    static_configs:
      - targets:
          - localhost
        labels:
          job: gewu-platform
          __path__: /var/log/containers/gewu-platform-*.log
```

### 6.2 日志查询

```logql
# 查询错误日志
{job="gewu-platform"} |= "ERROR"

# 查询慢查询
{job="gewu-platform"} |~ "slow-sql"

# 查询特定接口
{job="gewu-platform"} | json | uri_template="/api/v1/sessions"
```

## 7. Grafana 仪表盘

### 7.1 核心仪表盘

- **请求速率**: HTTP 请求量趋势
- **延迟分布**: P50/P95/P99 延迟
- **JVM 内存**: 堆内存使用趋势
- **GC 暂停**: GC 暂停时间趋势
- **数据库连接池**: 连接数趋势
- **Redis 缓存**: 内存和连接数
- **错误率**: 5xx 错误趋势
- **线程数**: 活跃线程趋势

### 7.2 自定义仪表盘

```json
{
  "title": "自定义仪表盘",
  "panels": [
    {
      "title": "业务指标",
      "targets": [
        {
          "expr": "sum(rate(gewu_session_created_total[5m]))",
          "legendFormat": "会话创建速率"
        }
      ]
    }
  ]
}
```

## 8. 最佳实践

### 8.1 指标命名

- 使用 snake_case
- 包含单位后缀 (seconds, bytes, total)
- 使用标签区分维度

### 8.2 告警配置

- 设置合理的持续时间避免误报
- 分级告警 (critical/warning)
- 配置通知去重

### 8.3 数据保留

- Prometheus: 30 天
- Grafana: 90 天
- 日志: 30 天

### 8.4 性能优化

- 使用 Recording Rules 预计算常用查询
- 限制标签基数
- 定期清理无用指标
