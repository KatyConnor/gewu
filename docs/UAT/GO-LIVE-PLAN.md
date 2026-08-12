# 格物平台上线发布方案

> 生产环境上线发布计划

## 1. 上线前检查

### 1.1 环境检查

```bash
# 检查服务器状态
kubectl -n gewu get nodes
kubectl -n gewu get pods

# 检查数据库状态
mysql -h production-db -u root -p -e "SELECT 1"

# 检查 Redis 状态
redis-cli -h production-redis ping
```

### 1.2 配置检查

```bash
# 检查配置文件
cat .env | grep -v PASSWORD | grep -v SECRET

# 检查 K8s 配置
kubectl -n gewu get configmap
kubectl -n gewu get secret
```

### 1.3 代码检查

```bash
# 检查代码质量
mvn sonar:sonar

# 检查安全漏洞
trivy fs --severity HIGH,CRITICAL .
```

## 2. 发布策略

### 2.1 灰度发布

```
阶段 1: 10% 流量 → 新版本
阶段 2: 50% 流量 → 新版本
阶段 3: 100% 流量 → 新版本
```

### 2.2 金丝雀发布

```yaml
# 金丝雀 Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gewu-platform-canary
spec:
  replicas: 1
  template:
    metadata:
      labels:
        app: gewu-platform
        version: canary
```

### 2.3 蓝绿发布

```bash
# 切换流量
kubectl patch service gewu-platform -p '{"spec":{"selector":{"version":"green"}}}'
```

## 3. 发布流程

### 3.1 发布前

1. 通知相关人员
2. 备份数据库
3. 检查监控告警
4. 准备回滚方案

### 3.2 发布中

1. 构建新版本镜像
2. 推送到容器仓库
3. 更新 Deployment
4. 监控部署状态
5. 验证服务功能

### 3.3 发布后

1. 检查服务状态
2. 验证业务功能
3. 监控性能指标
4. 通知发布完成

## 4. 回滚方案

### 4.1 回滚条件

- P0 缺陷出现
- 性能严重下降
- 用户投诉激增

### 4.2 回滚步骤

```bash
# Kubernetes 回滚
kubectl -n gewu rollout undo deploy/gewu-platform

# 验证回滚
kubectl -n gewu rollout status deploy/gewu-platform
```

### 4.3 回滚验证

```bash
# 检查服务状态
curl https://gewu.com/actuator/health

# 检查业务功能
# 执行核心业务流程验证
```

## 5. 发布清单

### 5.1 发布前

- [ ] 通知相关人员
- [ ] 备份数据库
- [ ] 检查监控告警
- [ ] 准备回滚方案
- [ ] 确认测试通过

### 5.2 发布中

- [ ] 构建新版本镜像
- [ ] 推送到容器仓库
- [ ] 更新 Deployment
- [ ] 监控部署状态
- [ ] 验证服务功能

### 5.3 发布后

- [ ] 检查服务状态
- [ ] 验证业务功能
- [ ] 监控性能指标
- [ ] 通知发布完成
- [ ] 更新发布记录

## 6. 通信模板

### 6.1 发布通知

```
【发布】格物平台 v1.0.0 发布通知

发布时间: 2024-01-01 10:00
发布内容: v1.0.0 正式版
影响范围: 全站服务
预计时长: 30 分钟

发布内容:
- 新增 AI 对话功能
- 优化工作流引擎
- 修复已知缺陷

感谢您的支持与理解。
```

### 6.2 发布完成通知

```
【完成】格物平台 v1.0.0 发布完成通知

完成时间: 2024-01-01 10:30
发布状态: 成功
影响时长: 30 分钟

新功能:
- AI 对话: 支持流式输出
- 工作流: 支持可视化设计
- 沙箱: 支持 Docker 容器

如有问题，请联系技术支持。
```

## 7. 监控告警

### 7.1 发布监控

- 部署状态
- Pod 状态
- 服务健康状态

### 7.2 业务监控

- 请求速率
- 响应时间
- 错误率

### 7.3 告警阈值

| 指标 | 阈值 | 说明 |
|------|------|------|
| 错误率 | > 1% | 服务异常 |
| P95 延迟 | > 500ms | 性能下降 |
| Pod 重启 | > 3 次 | 服务不稳定 |

## 8. 发布记录

### 8.1 发布历史

| 版本 | 日期 | 内容 | 状态 |
|------|------|------|------|
| v1.0.0 | 2024-01-01 | 正式版发布 | 成功 |

### 8.2 回滚历史

| 版本 | 日期 | 原因 | 结果 |
|------|------|------|------|
| - | - | - | - |

## 9. 联系方式

### 9.1 发布团队

| 角色 | 姓名 | 联系方式 |
|------|------|----------|
| 发布负责人 | | |
| 开发负责人 | | |
| 运维负责人 | | |
| 产品负责人 | | |

### 9.2 应急联系

- 值班电话: 400-xxx-xxxx
- 应急邮箱: emergency@gewu.com
