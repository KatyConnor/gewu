# CI/CD 流水线指南

> 格物平台持续集成与持续部署方案

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                    CI/CD 流水线                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  代码提交 → 代码检查 → 构建 → 测试 → 安全扫描 → 镜像构建      │
│      ↓                                                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ GitHub   │    │ GitLab   │    │ Jenkins  │              │
│  │ Actions  │    │ CI       │    │ Pipeline │              │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘              │
│       │              │              │                      │
│       └──────────────┼──────────────┘                      │
│                      ▼                                      │
│              ┌──────────────┐                               │
│              │ Docker       │                               │
│              │ Registry     │                               │
│              └──────┬───────┘                               │
│                     │                                       │
│         ┌───────────┼───────────┐                          │
│         ▼           ▼           ▼                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                   │
│  │ Staging  │ │ Production│ │ Rollback │                   │
│  │ 环境     │ │ 环境     │ │ 回滚     │                   │
│  └──────────┘ └──────────┘ └──────────┘                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 2. 流水线阶段

### 2.1 Build（构建）

```bash
mvn clean compile -T 4 -B
```

- 使用 Maven 3.9 + JDK 21
- 并行构建 (-T 4)
- 缓存依赖 (.m2/repository)

### 2.2 Test（测试）

```bash
mvn test
```

- 单元测试
- 集成测试
- 生成测试报告 (JUnit XML)

### 2.3 Security Scan（安全扫描）

```bash
trivy fs --severity CRITICAL,HIGH --exit-code 1 .
```

- 依赖漏洞扫描
- 容器镜像扫描
- 代码安全分析

### 2.4 Build Image（构建镜像）

```bash
docker build -t gewu/platform:${BUILD_NUMBER} .
docker push gewu/platform:${BUILD_NUMBER}
```

- 多阶段构建
- 版本标签 (BUILD_NUMBER + latest)
- 推送到容器仓库

### 2.5 Deploy（部署）

```bash
kubectl set image deployment/gewu-platform \
    gewu-platform=${IMAGE_NAME}:${BUILD_NUMBER} \
    -n gewu
kubectl rollout status deployment/gewu-platform -n gewu --timeout=300s
```

- 滚动更新
- 部署验证
- 自动回滚（失败时）

## 3. 环境配置

### 3.1 Staging 环境

- **用途**: 测试验证
- **触发**: 自动 (main 分支)
- **配置**: 与生产一致但资源较少

### 3.2 Production 环境

- **用途**: 正式发布
- **触发**: 手动审批
- **配置**: 完整资源

## 4. 配置文件

### 4.1 GitHub Actions

```yaml
# .github/workflows/ci-cd.yml
name: CI/CD Pipeline
on:
  push:
    branches: [main, dev]
  pull_request:
    branches: [main]
```

### 4.2 GitLab CI

```yaml
# .gitlab-ci.yml
stages:
  - build
  - test
  - security
  - build-image
  - deploy-staging
  - deploy-production
```

### 4.3 Jenkins

```groovy
// Jenkinsfile
pipeline {
    agent any
    stages {
        stage('Build') { ... }
        stage('Test') { ... }
        stage('Deploy') { ... }
    }
}
```

## 5. 环境变量

### 5.1 构建变量

| 变量 | 说明 | 示例 |
|------|------|------|
| BUILD_NUMBER | 构建编号 | 123 |
| COMMIT_SHA | 提交哈希 | abc1234 |
| BRANCH_NAME | 分支名 | main |

### 5.2 部署变量

| 变量 | 说明 | 示例 |
|------|------|------|
| KUBE_CONTEXT | K8s 上下文 | staging/production |
| NAMESPACE | 命名空间 | gewu |
| IMAGE_TAG | 镜像标签 | latest/1.0.0 |

## 6. 质量门禁

### 6.1 代码质量

- [ ] 单元测试通过
- [ ] 代码覆盖率 > 80%
- [ ] 无 SonarQube 严重问题

### 6.2 安全扫描

- [ ] 无 CRITICAL 漏洞
- [ ] 无 HIGH 漏洞（可忽略除外）
- [ ] 依赖版本合规

### 6.3 镜像构建

- [ ] 镜像大小 < 300MB
- [ ] 非 root 用户运行
- [ ] 健康检查配置

## 7. 部署策略

### 7.1 滚动更新

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

- 零停机部署
- 逐步替换旧版本
- 失败自动回滚

### 7.2 蓝绿部署

```bash
# 切换流量
kubectl patch service gewu-platform -p '{"spec":{"selector":{"version":"blue"}}}'
```

### 7.3 金丝雀发布

```bash
# 10% 流量到新版本
kubectl apply -f canary-deployment.yaml
```

## 8. 监控与告警

### 8.1 部署监控

- 部署频率
- 部署成功率
- 回滚率

### 8.2 运行时监控

- 应用健康状态
- 错误率变化
- 性能指标

## 9. 回滚流程

### 9.1 自动回滚

```bash
# K8s 自动回滚
kubectl rollout undo deployment/gewu-platform -n gewu
```

### 9.2 手动回滚

```bash
# 查看历史版本
kubectl rollout history deployment/gewu-platform -n gewu

# 回滚到指定版本
kubectl rollout undo deployment/gewu-platform -n gewu --to-revision=3
```

## 10. 最佳实践

### 10.1 分支策略

- `main`: 生产分支
- `dev`: 开发分支
- `feature/*`: 功能分支
- `hotfix/*`: 紧急修复

### 10.2 提交规范

```
feat(scope): 添加新功能
fix(scope): 修复 bug
docs(scope): 文档更新
chore(scope): 构建/工具变更
```

### 10.3 版本管理

- Semantic Versioning: MAJOR.MINOR.PATCH
- 镜像标签: git-sha / build-number / semantic-version

### 10.4 安全实践

- 敏感信息使用 Secret
- 镜像签名验证
- 最小权限原则
- 定期安全审计
