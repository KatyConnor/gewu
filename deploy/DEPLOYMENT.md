# 部署指南 (Deployment Guide)

> 格物平台生产环境部署文档 — Docker Compose / Kubernetes / 监控 / 回滚 / 备份

## 1. 环境要求

### 硬件最低配置
| 资源 | 最低配置 | 推荐配置 |
|------|---------|---------|
| CPU | 4 核 | 8 核 |
| 内存 | 8 GB | 16 GB |
| 磁盘 | 50 GB SSD | 100 GB SSD |
| 网络 | 100 Mbps | 1 Gbps |

### 软件依赖
- Docker 24+ / Docker Compose 2.20+
- Kubernetes 1.27+（K8s 部署方式）
- MySQL 8.0+ 或 OceanBase 4.4.2+
- DragonflyDB 1.27+（Redis 兼容）
- RocketMQ 5.1.4+（可选，事件驱动场景）

## 2. Docker Compose 生产部署

### 2.1 准备镜像
```bash
docker build -t gewu/platform:1.0.0-SNAPSHOT .
```

### 2.2 配置环境变量
复制 `.env.example` 为 `.env`，填写生产配置：
```bash
cp .env.example .env
# 修改 MYSQL_ROOT_PASSWORD / JWT_SECRET / SM4_KEY 等敏感参数
```

### 2.3 启动生产编排
```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f --tail=200
```

### 2.4 停止与清理
```bash
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml down -v   # 含数据卷清理，谨慎执行
```

## 3. Kubernetes 部署

按以下顺序 apply 6 个清单文件，依赖关系不可颠倒：
```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/deployment.yaml
kubectl apply -f deploy/k8s/service.yaml
kubectl apply -f deploy/k8s/ingress.yaml
kubectl apply -f deploy/k8s/hpa.yaml
```

验证部署：
```bash
kubectl -n gewu get pods,svc,ingress,hpa
kubectl -n gewu rollout status deploy/gewu-platform
```

## 4. 健康检查

Deployment 已配置两种探针：
- **livenessProbe**：`GET /actuator/health/liveness`，失败重启 Pod
- **readinessProbe**：`GET /actuator/health/readiness`，失败从 Service 流量中摘除

自定义健康指标 `DatabaseHealthIndicator` 会检视数据库连通性，异常时 readiness 转为 NOT_READY。

查看健康状态：
```bash
kubectl -n gewu exec <pod> -- curl -s localhost:8080/actuator/health | jq
```

## 5. 日志收集

后端使用 Logback，日志输出到 stdout 容器标准输出，建议接入 ELK / Loki：
- 集群侧用 Filebeat / Promtail 采集 `/var/log/containers`
- 关键字段：`traceId`、`userId`、`path`、`latencyMs`
- 审计日志（@AuditLog）独立索引，保留 ≥ 180 天

## 6. 监控告警

Prometheus 抓取地址：`/actuator/prometheus`，端口 8080。建议 Grafana 仪表盘包含：
- JVM：jvm_memory_used / jvm_threads_live / process_cpu_usage
- HTTP：http_server_requests_seconds（P95/P99）
- 业务：api_rate_limit_exceeded_total / idempotent_rejected_total
- DB：hikaricp_connections_active / db_health_check_failed_total

告警建议：
| 告警规则 | 阈值 | 持续 |
|---------|------|------|
| Pod 重启 | restarts > 3 | 5m |
| P95 延迟 | > 500ms | 5m |
| 限流拒绝率 | > 10% | 10m |
| DB 健康失败 | count > 0 | 1m |

## 7. 回滚流程

```bash
# 查看发布历史
kubectl -n gewu rollout history deploy/gewu-platform

# 回滚到上一版本
kubectl -n gewu rollout undo deploy/gewu-platform

# 回滚到指定版本
kubectl -n gewu rollout undo deploy/gewu-platform --to-revision=3
```

## 8. 数据库备份

建议使用 CronJob 每日全量备份，保留 30 天：
```bash
mysqldump -h <host> -u root -p<pass> --single-transaction \
  --routines --triggers gewu_platform | gzip > /backup/gewu_$(date +%F).sql.gz
```

K8s CronJob 示例（schedule: `0 2 * * *`）需挂载 PVC 并配置对象存储异地副本。

恢复演练每季度执行一次，验证 RTO < 30 分钟、RPO < 24 小时。

## 9. TLS 证书配置

推荐使用 cert-manager + Let's Encrypt 自动签发：
1. 安装 cert-manager：`kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.3/cert-manager.yaml`
2. 创建 `ClusterIssuer`（HTTP01 或 DNS01 challenge）
3. Ingress 注解指定 `cert-manager.io/cluster-issuer: letsencrypt-prod`
4. 证书自动续期，过期前 30 天 cert-manager 触发 renewal

强制 HTTPS：Ingress 配置 `nginx.ingress.kubernetes.io/ssl-redirect: "true"`。