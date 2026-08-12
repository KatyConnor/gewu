# 格物平台运维手册

> 生产环境运维操作指南

## 1. 日常运维

### 1.1 服务状态检查

```bash
# Docker 环境
docker compose -f docker-compose.prod.yml ps

# Kubernetes 环境
kubectl -n gewu get pods,svc,ingress,hpa
```

### 1.2 日志查看

```bash
# Docker 日志
docker compose -f docker-compose.prod.yml logs -f --tail=100 gewu-platform

# Kubernetes 日志
kubectl -n gewu logs -f deploy/gewu-platform --tail=100
```

### 1.3 健康检查

```bash
# 应用健康检查
curl http://localhost:8080/actuator/health

# 数据库健康检查
mysql -h localhost -u root -p -e "SELECT 1"

# Redis 健康检查
redis-cli ping
```

## 2. 性能监控

### 2.1 JVM 监控

```bash
# 查看 JVM 状态
jcmd 1 VM.flags
jcmd 1 GC.heap_info
jcmd 1 Thread.print
```

### 2.2 数据库监控

```sql
-- 查看连接数
SHOW STATUS LIKE 'Threads_connected';

-- 查看慢查询
SHOW STATUS LIKE 'Slow_queries';

-- 查看查询缓存
SHOW STATUS LIKE 'Qcache%';
```

### 2.3 Redis 监控

```bash
# 查看内存使用
redis-cli INFO memory

# 查看连接数
redis-cli INFO clients

# 查看命中率
redis-cli INFO stats | grep keyspace
```

## 3. 故障排查

### 3.1 服务不可用

```bash
# 检查容器状态
docker ps -a | grep gewu

# 检查端口占用
netstat -tlnp | grep 8080

# 检查日志
docker logs gewu-platform-prod --tail=50
```

### 3.2 数据库连接失败

```bash
# 检查 MySQL 状态
docker exec gewu-mysql-prod mysql -u root -p -e "SHOW PROCESSLIST"

# 检查连接池
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

### 3.3 内存溢出

```bash
# 生成堆转储
jmap -dump:live,format=b,file=heapdump.hprof 1

# 分析堆转储
jhat heapdump.hprof
```

## 4. 备份与恢复

### 4.1 数据库备份

```bash
# 全量备份
mysqldump -h localhost -u root -p --single-transaction gewu_prod | gzip > backup_$(date +%F).sql.gz

# 恢复备份
gunzip -c backup_2024-01-01.sql.gz | mysql -h localhost -u root -p gewu_prod
```

### 4.2 配置备份

```bash
# 备份配置文件
tar -czf config_$(date +%F).tar.gz .env deploy/k8s/

# 恢复配置
tar -xzf config_2024-01-01.tar.gz
```

## 5. 版本发布

### 5.1 Docker 发布

```bash
# 构建镜像
docker build -t gewu/platform:1.0.0 .

# 推送镜像
docker push gewu/platform:1.0.0

# 更新服务
docker compose -f docker-compose.prod.yml up -d
```

### 5.2 Kubernetes 发布

```bash
# 更新镜像
kubectl -n gewu set image deployment/gewu-platform \
    gewu-platform=gewu/platform:1.0.0

# 查看发布状态
kubectl -n gewu rollout status deploy/gewu-platform

# 回滚发布
kubectl -n gewu rollout undo deploy/gewu-platform
```

## 6. 安全运维

### 6.1 密钥轮换

```bash
# 更新 JWT Secret
kubectl -n gewu create secret generic gewu-platform-secret \
    --from-literal=jwt-secret=new-secret \
    --dry-run=client -o yaml | kubectl apply -f -

# 重启服务
kubectl -n gewu rollout restart deploy/gewu-platform
```

### 6.2 证书更新

```bash
# 更新 TLS 证书
kubectl -n gewu create secret tls gewu-tls \
    --cert=tls.crt --key=tls.key \
    --dry-run=client -o yaml | kubectl apply -f -
```

### 6.3 安全扫描

```bash
# 镜像扫描
trivy image gewu/platform:latest

# 依赖扫描
mvn dependency-check:check
```

## 7. 扩缩容

### 7.1 手动扩缩容

```bash
# 扩容
kubectl -n gewu scale deployment/gewu-platform --replicas=5

# 缩容
kubectl -n gewu scale deployment/gewu-platform --replicas=2
```

### 7.2 自动扩缩容

```yaml
# HPA 配置
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

## 8. 灾难恢复

### 8.1 RTO/RPO 目标

| 指标 | 目标 | 说明 |
|------|------|------|
| RTO | < 30 分钟 | 恢复时间目标 |
| RPO | < 24 小时 | 恢复点目标 |

### 8.2 恢复流程

1. 评估故障范围
2. 启动应急预案
3. 恢复数据备份
4. 验证服务状态
5. 通知相关人员

## 9. 运维脚本

```bash
# 部署脚本
./deploy/scripts/deploy.sh docker

# 回滚脚本
./deploy/scripts/rollback.sh docker

# 健康检查
./deploy/scripts/health-check.sh

# 性能调优
./deploy/scripts/perf-tune.sh check

# 安全审计
./deploy/scripts/security-audit.sh full

# 数据库备份
./deploy/scripts/backup.sh full
```

## 10. 联系方式

- 运维负责人: ops@gewu.com
- 值班电话: 400-xxx-xxxx
- 应急响应: emergency@gewu.com
