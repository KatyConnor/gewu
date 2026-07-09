# 格物平台 - 监控告警文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 监控告警文档 |
| 版本 | V5.0 (更新) |
| 创建日期 | 2026-07-07 |
| 最后更新 | 2026-07-07 |
| 文档状态 | 已批准 |
| 项目根目录 | /home/wnn/devcode/ai-code/gewu-platform |

---

## 1. 监控概述

格物平台采用 **Nightingale (夜莺) + VictoriaMetrics + Grafana** 的监控方案，提供全链路监控、实时告警、可视化分析能力。

### 1.1 监控架构

```
┌─────────────────────────────────────────────────────────┐
│                    监控平台架构                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Nightingale │  │  VictoriaMetrics│  │  Grafana     │  │
│  │  (监控平台)   │  │  (时序数据库)   │  │  (可视化)     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  Categraf    │  │  Prometheus   │  │  ELK         │  │
│  │  (采集器)     │  │  (备选采集)    │  │  (日志)       │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│                    监控对象                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  应用服务     │  │  数据库       │  │  缓存         │  │
│  │  (QPS/延迟)   │  │  (连接数/慢查询)│  │  (命中率)     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  消息队列     │  │  沙箱服务     │  │  系统资源     │  │
│  │  (堆积/延迟)  │  │  (执行时间)    │  │  (CPU/内存)   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 监控工具选型

### 2.1 监控工具对比

| 工具 | 状态 | 说明 |
|------|------|------|
| **Nightingale** | ✅ 主力 | 夜莺监控平台，Open-Falcon 继承者 |
| **VictoriaMetrics** | ✅ 主力 | 时序数据库，性能优异 |
| **Grafana** | ✅ 主力 | 可视化平台 |
| **Categraf** | ✅ 主力 | 监控数据采集器 |
| **Prometheus** | ⚠️ 备选 | 备用采集器 |
| **ELK** | ⚠️ 备选 | 日志分析 |

### 2.2 Nightingale 特性

| 特性 | 说明 |
|------|------|
| **信创合规** | ✅ CCF ODC 托管 |
| **活跃维护** | ✅ v7 LTS 持续更新 |
| **告警引擎** | 推拉结合，支持复杂告警规则 |
| **数据源** | 支持 Prometheus、VictoriaMetrics、ES、Loki 等 |
| **可视化** | 内置仪表盘、模板中心 |
| **采集器** | Categraf 统一采集 |

---

## 3. 监控指标

### 3.1 应用服务监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **性能指标** | QPS | 每秒请求数 | < 10,000 |
| **性能指标** | P95 延迟 | 95% 请求延迟 | < 100ms |
| **性能指标** | P99 延迟 | 99% 请求延迟 | < 200ms |
| **性能指标** | 错误率 | 错误请求比例 | < 1% |
| **资源指标** | CPU 使用率 | CPU 使用率 | < 80% |
| **资源指标** | 内存使用率 | 内存使用率 | < 80% |
| **资源指标** | GC 时间 | GC 耗时 | < 10% |

### 3.2 数据库监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **连接指标** | 连接数 | 当前连接数 | < 80% 额定值 |
| **连接指标** | 慢查询数 | 慢查询数量 | = 0 |
| **性能指标** | 查询延迟 | 平均查询延迟 | < 50ms |
| **性能指标** | TPS | 每秒事务数 | > 1,000 |
| **性能指标** | QPS | 每秒查询数 | > 10,000 |
| **资源指标** | 缓存命中率 | 缓存命中率 | > 90% |

### 3.3 缓存监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **性能指标** | QPS | 每秒操作数 | > 100,000 |
| **性能指标** | P95 延迟 | 95% 操作延迟 | < 1ms |
| **性能指标** | 命中率 | 缓存命中率 | > 90% |
| **资源指标** | 内存使用率 | 内存使用率 | < 80% |
| **资源指标** | 淘汰率 | 缓存淘汰率 | < 5% |

### 3.4 消息队列监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **性能指标** | TPS | 每秒事务数 | > 10,000 |
| **性能指标** | 延迟 | 消息延迟 | < 10ms |
| **性能指标** | 堆积量 | 消息堆积数量 | = 0 |
| **性能指标** | 消费速度 | 消息消费速度 | > 0 |
| **资源指标** | 消费者数 | 消费者数量 | > 0 |

### 3.5 沙箱监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **性能指标** | 启动时间 | MicroVM 启动时间 | < 10s |
| **性能指标** | 执行时间 | 沙箱执行时间 | < 30s |
| **资源指标** | 内存开销 | 内存开销 | < 50MB |
| **资源指标** | CPU 开销 | CPU 开销 | < 10% |
| **资源指标** | 存储 IOPS | 存储 IOPS | > 1000 |

### 3.6 系统资源监控

| 指标类别 | 指标名称 | 说明 | 阈值 |
|----------|----------|------|------|
| **系统指标** | CPU 使用率 | CPU 使用率 | < 80% |
| **系统指标** | 内存使用率 | 内存使用率 | < 80% |
| **系统指标** | 磁盘使用率 | 磁盘使用率 | < 80% |
| **系统指标** | 网络流量 | 网络流量 | 正常 |
| **系统指标** | 磁盘 IOPS | 磁盘 IOPS | 正常 |

---

## 4. 监控实现

### 4.1 Categraf 配置

#### 4.1.1 基础配置

```yaml
# categraf 配置示例
global:
  interval: 15s
  hostname: gewu-app-01

[[inputs.cpu]]
  percpu = true
  totalcpu = true
  collect_cpu_time = false
  report_active_cores = true

[[inputs.mem]]
  [[inputs.mem.pass]]
    include = ["^/.*"]

[[inputs.disk]]
  mount_points = ["/"]
  ignore_fs = ["tmpfs", "devtmpfs", "fuse.lxcfs", "overlay"]

[[inputs.net]]
  interfaces = ["eth0"]
  ignore_protocol = false

[[inputs.processes]]
  include = ["java", "nginx", "mysql"]

[[inputs.http_response]]
  urls = [
    "http://localhost:8080/actuator/health",
    "http://localhost:3306/health",
    "http://localhost:6379/ping"
  ]
  timeout = 5s
  response_status = [200, 302]
```

#### 4.1.2 应用服务监控

```yaml
[[inputs.http_response]]
  urls = [
    "http://localhost:8080/actuator/metrics/http.server.requests",
    "http://localhost:8080/actuator/metrics/jvm.memory.used",
    "http://localhost:8080/actuator/metrics/jvm.gc.pause"
  ]
  response_status = [200]
  name_override = "gewu_application"
```

#### 4.1.3 数据库监控

```yaml
[[inputs.mysql]]
  servers = ["root:password@tcp(localhost:3306)/"]
  tables = ["information_schema.processlist", "performance_schema.status_by_host"]
  table_schema = false

[[inputs.redis]]
  servers = ["tcp://localhost:6379"]
  password = ""
  db = 0
```

#### 4.1.4 消息队列监控

```yaml
[[inputs.rocketmq]]
  namesrv_addrs = ["localhost:9876"]
  consumer_groups = ["gewu-consumer"]
  topics = ["gewu-events"]
```

### 4.2 Nightingale 配置

#### 4.2.1 告警规则

```yaml
# 告警规则示例
groups:
  - name: gewu_application
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_total{status=~"5.."}[5m]) > 0.01
        for: 5m
        labels:
          severity: critical
          team: backend
        annotations:
          summary: "应用错误率过高"
          description: "应用 {{ $labels.instance }} 错误率超过 1%"

      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 0.1
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "应用延迟过高"
          description: "应用 {{ $labels.instance }} P95 延迟超过 100ms"

      - alert: HighCPU
        expr: rate(cpu_usage_percent[5m]) > 80
        for: 5m
        labels:
          severity: warning
          team: devops
        annotations:
          summary: "CPU 使用率过高"
          description: "主机 {{ $labels.instance }} CPU 使用率超过 80%"
```

#### 4.2.2 仪表盘

```yaml
# 仪表盘示例
apiVersion: v1
kind: ConfigMap
metadata:
  name: gewu-dashboard
  namespace: monitoring
data:
  gewu-overview.json: |
    {
      "title": "格物平台总览",
      "panels": [
        {
          "title": "QPS",
          "targets": [
            {
              "expr": "rate(http_server_requests_total[1m])"
            }
          ]
        },
        {
          "title": "P95 延迟",
          "targets": [
            {
              "expr": "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))"
            }
          ]
        },
        {
          "title": "错误率",
          "targets": [
            {
              "expr": "rate(http_server_requests_total{status=~"5.."}[5m])"
            }
          ]
        }
      ]
    }
```

### 4.3 Grafana 可视化

#### 4.3.1 仪表盘

1. **应用服务仪表盘**
   - QPS 趋势图
   - P95/P99 延迟图
   - 错误率图
   - 资源使用率图

2. **数据库仪表盘**
   - 连接数趋势图
   - 慢查询趋势图
   - 查询延迟图
   - TPS/QPS 图

3. **缓存仪表盘**
   - QPS 趋势图
   - 命中率图
   - 内存使用率图
   - 淘汰率图

4. **消息队列仪表盘**
   - TPS 趋势图
   - 延迟图
   - 堆积量图
   - 消费速度图

#### 4.3.2 仪表盘配置

```json
{
  "dashboard": {
    "title": "格物平台 - 应用服务监控",
    "panels": [
      {
        "id": 1,
        "title": "QPS",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(http_server_requests_total[1m])"
          }
        ]
      },
      {
        "id": 2,
        "title": "P95 延迟",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))"
          }
        ]
      }
    ]
  }
}
```

---

## 5. 告警配置

### 5.1 告警级别

| 级别 | 说明 | 响应时间 |
|------|------|----------|
| **Critical** | 严重故障 | < 5 分钟 |
| **High** | 高优先级 | < 15 分钟 |
| **Medium** | 中优先级 | < 30 分钟 |
| **Low** | 低优先级 | < 1 小时 |

### 5.2 告警规则

#### 5.2.1 应用服务告警

| 告警名称 | 触发条件 | 级别 | 响应人 |
|----------|----------|------|--------|
| **HighErrorRate** | 错误率 > 1% | Critical | 后端开发 |
| **HighLatency** | P95 延迟 > 100ms | High | 后端开发 |
| **HighCPU** | CPU 使用率 > 80% | Medium | DevOps |
| **OutOfMemory** | 内存使用率 > 90% | Critical | DevOps |

#### 5.2.2 数据库告警

| 告警名称 | 触发条件 | 级别 | 响应人 |
|----------|----------|------|--------|
| **HighConnectionCount** | 连接数 > 80% 额定值 | High | DBA |
| **SlowQuery** | 慢查询数量 > 0 | Medium | DBA |
| **DatabaseDown** | 数据库不可用 | Critical | DBA |

#### 5.2.3 缓存告警

| 告警名称 | 触发条件 | 级别 | 响应人 |
|----------|----------|------|--------|
| **LowCacheHitRate** | 缓存命中率 < 90% | Medium | 后端开发 |
| **CacheEvictionRate** | 缓存淘汰率 > 5% | Medium | 后端开发 |
| **CacheDown** | 缓存不可用 | Critical | 后端开发 |

#### 5.2.4 消息队列告警

| 告警名称 | 触发条件 | 级别 | 响应人 |
|----------|----------|------|--------|
| **HighMessageBacklog** | 消息堆积 > 10000 | High | 后端开发 |
| **HighMessageDelay** | 消息延迟 > 30s | Medium | 后端开发 |
| **MQDown** | 消息队列不可用 | Critical | DevOps |

### 5.3 告警通知

#### 5.3.1 通知方式

| 方式 | 说明 | 优先级 |
|------|------|--------|
| **邮件** | 邮件通知 | High/Medium |
| **短信** | 短信通知 | Critical |
| **钉钉** | 钉钉通知 | Critical/High |
| **企业微信** | 企业微信通知 | Critical/High |

#### 5.3.2 通知配置

```yaml
# Nightingale 通知配置
alerts:
  email:
    - name: "后端开发组"
      email: "backend@example.com"
      level: "critical,high"
  dingding:
    - name: "紧急告警"
      webhook: "https://oapi.dingtalk.com/robot/send?access_token=xxxxx"
      level: "critical"
  wechat:
    - name: "紧急告警"
      webhook: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxxx"
      level: "critical"
```

---

## 6. 日志监控

### 6.1 日志收集

| 工具 | 说明 |
|------|------|
| **ELK** | Elasticsearch + Logstash + Kibana |
| **Loki** | Grafana Loki 日志系统 |

### 6.2 日志采集

```yaml
# Filebeat 配置
filebeat.inputs:
  - type: log
    enabled: true
    paths:
      - /var/log/gewu/*.log
    fields:
      app: gewu
      env: production

output.elasticsearch:
  hosts: ["elasticsearch:9200"]
  index: "gewu-logs-%{+yyyy.MM.dd}"
```

### 6.3 日志查询

```bash
# 查询错误日志
curl -X GET "http://elasticsearch:9200/gewu-logs-*/_search?q=ERROR"

# 查询慢查询日志
curl -X GET "http://elasticsearch:9200/gewu-logs-*/_search?q=slow_query"
```

---

## 7. 告警演练

### 7.1 告警演练计划

| 演练类型 | 频率 | 说明 |
|----------|------|------|
| **功能演练** | 每月 | 验证告警规则有效性 |
| **响应演练** | 每季度 | 验证告警响应流程 |
| **恢复演练** | 每半年 | 验证故障恢复流程 |

### 7.2 演练场景

1. **应用服务故障**
   - 模拟应用服务宕机
   - 验证告警通知
   - 验证故障恢复

2. **数据库故障**
   - 模拟数据库宕机
   - 验证告警通知
   - 验证故障恢复

3. **缓存故障**
   - 模拟缓存宕机
   - 验证告警通知
   - 验证故障恢复

---

## 8. 监控维护

### 8.1 监控检查

| 检查项 | 频率 | 负责人 |
|--------|------|--------|
| **监控数据** | 每日 | DevOps |
| **告警规则** | 每周 | DevOps |
| **仪表盘** | 每月 | DevOps |
| **告警演练** | 每季度 | DevOps |

### 8.2 监控优化

1. **指标优化**
   - 定期清理无用指标
   - 优化指标采集频率
   - 减少指标存储开销

2. **告警优化**
   - 定期审查告警规则
   - 调整告警阈值
   - 优化告警通知

3. **可视化优化**
   - 优化仪表盘布局
   - 添加新仪表盘
   - 优化查询性能

---

## 9. 附录

### 9.1 监控指标清单

| 指标类别 | 指标名称 | 单位 | 说明 |
|----------|----------|------|------|
| **应用服务** | QPS | 次/秒 | 每秒请求数 |
| **应用服务** | P95 延迟 | ms | 95% 请求延迟 |
| **应用服务** | 错误率 | % | 错误请求比例 |
| **数据库** | 连接数 | 个 | 当前连接数 |
| **数据库** | 慢查询数 | 个 | 慢查询数量 |
| **缓存** | QPS | 次/秒 | 每秒操作数 |
| **缓存** | 命中率 | % | 缓存命中率 |
| **消息队列** | TPS | 次/秒 | 每秒事务数 |
| **消息队列** | 堆积量 | 个 | 消息堆积数量 |

### 9.2 相关文档

| 文档 | 版本 | 说明 |
|------|------|------|
| 01-technical-architecture.md | V5.0 | 技术架构总览 |
| 02-deployment-architecture.md | V5.0 | 部署架构设计 |
| 03-database-design.md | V5.0 | 数据库设计 |
| 07-security-compliance.md | V5.0 | 安全合规文档 |

---

**文档结束**
