# 格物平台灾难恢复手册

> 生产环境灾难恢复预案

## 1. 灾难分类

### 1.1 P0 级灾难（服务不可用）

- 数据库宕机
- 应用服务全部不可用
- 网络中断
- 数据泄露

### 1.2 P1 级灾难（服务降级）

- 部分服务不可用
- 性能严重下降
- 数据部分丢失

### 1.3 P2 级灾难（轻微影响）

- 单个功能异常
- 性能轻微下降
- 非核心服务异常

## 2. 应急响应流程

### 2.1 响应时间

| 级别 | 响应时间 | 处理时间 | 升级时间 |
|------|----------|----------|----------|
| P0 | 15 分钟 | 2 小时 | 30 分钟 |
| P1 | 30 分钟 | 4 小时 | 1 小时 |
| P2 | 2 小时 | 8 小时 | 4 小时 |

### 2.2 响应团队

| 角色 | 职责 | 联系方式 |
|------|------|----------|
| 值班工程师 | 初步响应 | oncall@gewu.com |
| 技术负责人 | 技术决策 | tech-lead@gewu.com |
| 运维负责人 | 运维协调 | ops@gewu.com |
| 产品负责人 | 业务协调 | product@gewu.com |

### 2.3 响应流程

1. **检测**: 监控告警、用户反馈
2. **评估**: 确定灾难级别和影响范围
3. **响应**: 启动应急预案
4. **恢复**: 执行恢复操作
5. **验证**: 确认服务恢复正常
6. **复盘**: 分析原因、改进措施

## 3. 数据库灾难恢复

### 3.1 数据库宕机

```bash
# 检查数据库状态
docker exec gewu-mysql-prod mysql -u root -p -e "SELECT 1"

# 重启数据库
docker compose -f docker-compose.prod.yml restart mysql

# 检查数据完整性
docker exec gewu-mysql-prod mysql -u root -p -e "CHECK TABLE gewu.users"
```

### 3.2 数据损坏恢复

```bash
# 从备份恢复
gunzip -c /backup/gewu_2024-01-01.sql.gz | mysql -h localhost -u root -p gewu_prod

# 验证数据
mysql -h localhost -u root -p -e "SELECT COUNT(*) FROM gewu.users"
```

### 3.3 数据库主从切换

```bash
# 停止主库
docker stop gewu-mysql-primary

# 提升从库为主库
docker exec gewu-mysql-replica mysql -u root -p -e "STOP SLAVE; RESET SLAVE ALL;"

# 更新应用配置
kubectl -n gewu set env deployment/gewu-platform DB_HOST=mysql-replica
```

## 4. 应用服务灾难恢复

### 4.1 服务不可用

```bash
# 检查服务状态
kubectl -n gewu get pods

# 重启服务
kubectl -n gewu rollout restart deploy/gewu-platform

# 检查日志
kubectl -n gewu logs -f deploy/gewu-platform --tail=100
```

### 4.2 版本回滚

```bash
# 查看历史版本
kubectl -n gewu rollout history deploy/gewu-platform

# 回滚到上一版本
kubectl -n gewu rollout undo deploy/gewu-platform

# 回滚到指定版本
kubectl -n gewu rollout undo deploy/gewu-platform --to-revision=3
```

### 4.3 扩容恢复

```bash
# 紧急扩容
kubectl -n gewu scale deployment/gewu-platform --replicas=10

# 检查 Pod 状态
kubectl -n gewu get pods -w
```

## 5. 网络灾难恢复

### 5.1 网络中断

```bash
# 检查网络状态
ping localhost
curl http://localhost:8080/actuator/health

# 检查 DNS 解析
nslookup gewu.com

# 检查防火墙规则
iptables -L -n
```

### 5.2 证书过期

```bash
# 检查证书有效期
openssl x509 -in /etc/ssl/certs/gewu.crt -noout -dates

# 更新证书
kubectl -n gewu create secret tls gewu-tls \
    --cert=new.crt --key=new.key \
    --dry-run=client -o yaml | kubectl apply -f -
```

## 6. 数据泄露应急

### 6.1 检测泄露

```bash
# 检查异常访问
grep "unauthorized" /var/log/gewu/access.log

# 检查数据导出
grep "export" /var/log/gewu/audit.log
```

### 6.2 应急措施

1. 立即隔离受影响系统
2. 重置所有用户密码
3. 吊销所有 Token
4. 通知受影响用户
5. 报告监管部门

## 7. 备份策略

### 7.1 备份类型

| 类型 | 频率 | 保留时间 | 说明 |
|------|------|----------|------|
| 全量备份 | 每日 02:00 | 30 天 | 数据库全量 |
| 增量备份 | 每小时 | 7 天 | 数据库增量 |
| 配置备份 | 每次变更 | 90 天 | 配置文件 |
| 日志备份 | 每周 | 180 天 | 访问日志 |

### 7.2 备份验证

```bash
# 验证备份完整性
gzip -t /backup/gewu_2024-01-01.sql.gz

# 恢复测试
gunzip -c /backup/gewu_2024-01-01.sql.gz | mysql -h test-host -u root -p gewu_test
```

### 7.3 异地备份

```bash
# 上传到对象存储
aws s3 cp /backup/gewu_2024-01-01.sql.gz s3://gewu-backups/

# 从对象存储恢复
aws s3 cp s3://gewu-backups/gewu_2024-01-01.sql.gz /backup/
```

## 8. 恢复演练

### 8.1 演练频率

- P0 灾难: 每季度 1 次
- P1 灾难: 每半年 1 次
- P2 灾难: 每年 1 次

### 8.2 演练流程

1. 制定演练计划
2. 通知相关人员
3. 执行演练操作
4. 记录演练结果
5. 分析改进措施

### 8.3 演练检查清单

- [ ] 备份文件完整性
- [ ] 恢复操作可行性
- [ ] RTO 目标达成
- [ ] RPO 目标达成
- [ ] 服务功能正常
- [ ] 监控告警正常

## 9. 通信模板

### 9.1 故障通知

```
【紧急】格物平台故障通知

故障时间: 2024-01-01 10:00
影响范围: 全站服务不可用
故障级别: P0
当前状态: 处理中
预计恢复: 2024-01-01 12:00

技术团队正在全力处理，给您带来不便敬请谅解。
```

### 9.2 恢复通知

```
【恢复】格物平台服务恢复通知

恢复时间: 2024-01-01 11:30
影响时长: 1.5 小时
故障原因: 数据库连接池耗尽
处理措施: 扩容数据库连接池 + 重启服务
后续改进: 优化连接池配置

感谢您的理解与支持。
```

## 10. 联系方式

- 应急响应: emergency@gewu.com
- 值班电话: 400-xxx-xxxx
- 技术负责人: tech-lead@gewu.com
- 运维负责人: ops@gewu.com
