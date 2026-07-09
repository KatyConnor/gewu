# 格物平台 (Gewu Platform)

> AI 驱动的智能开发协作平台 — DDD + CQRS + Spring Boot 3.2 + React 18

## 项目简介

格物平台是一个基于 DDD（领域驱动设计）架构的 AI 开发协作平台，提供项目管理、会话协作、AI Agent、工作流引擎等核心功能，支持国密算法（SM2/SM3/SM4），满足等保 2.0 三级合规要求。

## 技术栈

### 后端
- Java 21 + Spring Boot 3.2.5
- MyBatis-Plus 3.5.5（ORM）
- Redisson 3.27.0（分布式锁）
- RocketMQ 5.1.4（消息队列）
- Bouncy Castle 1.77（国密算法 SM2/SM3/SM4）
- Flyway（数据库迁移）
- Spring Security + JWT（认证授权）
- SpringDoc OpenAPI 3.0（API 文档）
- Micrometer + Prometheus（监控）

### 前端
- React 18 + TypeScript 5
- Ant Design 5.15
- React Flow 11.11（工作流设计器）
- Vite 5.1（构建工具）
- Axios 1.6（HTTP 客户端）

### 基础设施
- MySQL 8.0（开发数据库） / OceanBase 4.4.2（生产数据库）
- DragonflyDB 1.27（Redis 兼容缓存）
- Docker + Kubernetes（容器化部署）

## 项目结构

```
gewu-platform/
├── gewu-common/           # 公共模块（ULID、国密、JWT、统一返回）
├── gewu-domain/            # 领域层（32 张表领域实体）
├── gewu-infrastructure/    # 基础设施层（Mapper、缓存、锁、MQ、监控）
├── gewu-application/        # 应用层（认证、用户、项目、会话、Agent、工作流）
├── gewu-interface/          # 接口层（Controller、Security、SSE、异常处理）
├── gewu-gateway/            # 网关模块（基础框架）
├── gewu-sandbox/            # 沙箱模块（基础框架）
├── gewu-web/                # 前端（React + TypeScript + Ant Design）
├── deploy/
│   ├── k8s/                # K8s 部署清单（6 文件）
│   └── scripts/            # 数据库 DDL 脚本
├── docs/design/            # 设计文档（21 份 + 4 份 Spike）
├── Dockerfile              # 多阶段 Docker 构建
├── docker-compose.yml      # 开发环境
├── docker-compose.prod.yml # 生产环境
├── Jenkinsfile             # CI/CD 流水线
├── CONTRIBUTING.md         # 代码规范
└── pom.xml                 # Maven 多模块
```

## 快速开始

### 1. 环境要求
- Java 21+
- Maven 3.8+
- Node.js 18+
- Docker 20+
- Docker Compose 2+

### 2. 启动开发环境
```bash
# 启动基础设施服务（MySQL + DragonflyDB + RocketMQ）
docker compose up -d

# 安装前端依赖
cd gewu-web && npm install

# 编译后端
mvn clean compile

# 启动后端应用
mvn spring-boot:run -pl gewu-interface

# 启动前端开发服务器
cd gewu-web && npm run dev
```

### 3. 访问应用
- 前端: http://localhost:3000
- 后端 API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Prometheus: http://localhost:8080/actuator/prometheus

### 4. 数据库迁移
应用启动时 Flyway 会自动执行数据库迁移，创建 32 张表和初始数据。

## 核心功能

| 模块 | 功能 | API 数 |
|------|------|--------|
| 认证管理 | 注册、登录、令牌刷新、登出 | 4 |
| 用户管理 | 用户查询、更新 | 4 |
| 项目管理 | 项目 CRUD、成员管理 | 10 |
| 会话消息 | 会话 CRUD、消息收发、搜索 | 13 |
| Agent 系统 | Agent CRUD、工具管理、执行记录 | 16 |
| 工作流引擎 | 工作流定义、实例、审批 | 21 |
| 实时推送 | SSE 消息推送 | 1 |
| 监控 | 健康检查、Prometheus | 2 |
| **总计** | | **71** |

## 安全特性

- 国密 SM2/SM3/SM4 算法集成
- JWT 认证（Access Token 30min + Refresh Token 7天）
- 等保 2.0 密码策略
- XSS 防护过滤器
- 安全响应头（CSP / X-Frame-Options / HSTS）
- API 限流（@RateLimit）
- 接口防重提交（@Idempotent）
- 审计日志（@AuditLog）

## 性能指标

| 指标 | 目标 | 实际 |
|------|------|------|
| 获取用户 P95 | < 200ms | < 1ms |
| 创建项目 P95 | < 500ms | 5.67ms |
| 发送消息 P95 | < 500ms | 6.06ms |
| 创建工作流 P95 | < 500ms | 3.48ms |

## 测试

```bash
# 单元测试（90 个测试用例）
mvn test -pl gewu-common

# E2E 集成测试
mvn test -pl gewu-interface -Dtest=E2EIntegrationTest

# 性能基准测试
mvn test -pl gewu-interface -Dtest=PerformanceBenchmarkTest
```

## API 文档

应用启动后访问 Swagger UI：http://localhost:8080/swagger-ui.html

## 生产部署

### Docker 构建
```bash
docker build -t gewu/platform:1.0.0-SNAPSHOT .
```

### K8s 部署
```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/deployment.yaml
kubectl apply -f deploy/k8s/service.yaml
kubectl apply -f deploy/k8s/ingress.yaml
kubectl apply -f deploy/k8s/hpa.yaml
```

## 文档

| 文档 | 路径 |
|------|------|
| 架构设计 | docs/design/21-unified-architecture.md |
| 数据库设计 | docs/design/22-unified-db-schema.md |
| API 规范 | docs/design/23-unified-api-spec.md |
| 安全设计 | docs/design/24-unified-security.md |
| 部署指南 | docs/design/25-unified-deployment.md |
| 开发路线图 | docs/design/33-dev-roadmap.md |
| 代码规范 | CONTRIBUTING.md |
| 部署文档 | deploy/DEPLOYMENT.md |
| 变更日志 | docs/CHANGELOG.md |

## 代码统计

| 项目 | 数量 |
|------|------|
| 后端 Java 文件 | 187 |
| 前端 TS/TSX 文件 | 15 |
| 测试文件 | 11 |
| 测试用例 | 92 |
| API 端点 | 71 |
| 数据库表 | 32 |
| K8s 部署清单 | 6 |

## License

Proprietary - © 2026 格物平台