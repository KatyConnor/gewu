# 格物平台 — 开发实施路线图

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 开发实施路线图 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 关联设计文档 | 20-unified-prd.md, 21-unified-architecture.md, 22-unified-db-schema.md, 23-unified-api-spec.md, 24-unified-security.md, 25-unified-deployment.md, 32-test-strategy.md |
| 总工期 | 22 周（约 5.5 个月） |
| 团队规模 | 8-10 人 |

---

## 目录

1. [总体计划](#1-总体计划)
2. [团队组织](#2-团队组织)
3. [技术 Spike 验证（Phase 0）](#3-技术-spike-验证phase-0)
4. [WBS 工作分解结构](#4-wbs-工作分解结构)
5. [Sprint 详细计划](#5-sprint-详细计划)
6. [里程碑与交付物](#6-里程碑与交付物)
7. [风险管理](#7-风险管理)
8. [质量门禁](#8-质量门禁)
9. [CI/CD 集成](#9-cicd-集成)
10. [附录](#10-附录)

---

## 1. 总体计划

### 1.1 阶段总览

```
Phase 0        Phase 1         Phase 2          Phase 3         Phase 4        Phase 5
准备期          基础设施层       核心模块          工作流模块       集成测试       迁移上线
(2周)          (4周)           (6周)            (4周)           (3周)          (3周)
├──Sprint 0──┤──Sprint 1~2──┤──Sprint 3~8──┤──Sprint 9~12──┤──Sprint 13~15─┤──Sprint 16~18─┤
   技术Spike     权限/缓存/DB    Session/Agent    工作流引擎      全链路测试     数据迁移
   环境搭建      MQ/网关        沙箱/SSE         工作流API       性能测试       兼容层
   开发规范      基础联调        核心联调         工作流UI        安全测试       生产部署
```

### 1.2 甘特图

| 阶段 | W1 | W2 | W3 | W4 | W5 | W6 | W7 | W8 | W9 | W10 | W11 | W12 | W13 | W14 | W15 | W16 | W17 | W18 | W19 | W20 | W21 | W22 |
|------|----|----|----|----|----|----|----|----|----|----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|
| Phase 0 准备期 | ██ | ██ | | | | | | | | | | | | | | | | | | | | |
| Phase 1 基础设施 | | | ██ | ██ | ██ | ██ | | | | | | | | | | | | | | | | |
| Phase 2 核心模块 | | | | | | | ██ | ██ | ██ | ██ | ██ | ██ | | | | | | | | | | |
| Phase 3 工作流 | | | | | | | | | | | | | ██ | ██ | ██ | ██ | | | | | | |
| Phase 4 集成测试 | | | | | | | | | | | | | | | | ██ | ██ | ██ | | | | |
| Phase 5 迁移上线 | | | | | | | | | | | | | | | | | | | ██ | ██ | ██ | ██ |

### 1.3 关键约束

| 约束 | 说明 |
|------|------|
| 信创环境 | 开发环境使用 Docker，生产环境使用 iSulad + 国产 CPU/OS |
| 国密合规 | 所有加密操作使用 SM2/SM3/SM4，Phase 0 完成 Bouncy Castle 集成验证 |
| 等保 2.0 三级 | 审计日志、权限控制、数据加密必须在 Phase 1 落地 |
| 数据库 | OceanBase 4.2+ 为主选，达梦 DM8 为备选，Phase 0 完成 K8s 部署验证 |
| 性能目标 | 核心 API P95 < 200ms，非核心 API P95 < 500ms（详见 21-unified-architecture.md §9.3） |

---

## 2. 团队组织

### 2.1 角色分配

| 角色 | 人数 | 职责 | 贯穿阶段 |
|------|------|------|---------|
| **架构师** | 1 | 技术方案评审、Spike 验证、代码审查 | Phase 0-5 |
| **后端 Tech Lead** | 1 | 核心模块设计、API 规范、DDD 落地 | Phase 0-5 |
| **后端开发 A** | 2 | Session/Agent/Tool 模块 | Phase 1-4 |
| **后端开发 B** | 2 | 工作流引擎/沙箱/网关 | Phase 1-4 |
| **前端开发** | 1-2 | React 18 + Ant Design + React Flow | Phase 2-4 |
| **DBA** | 1 | OceanBase 部署、DDL 优化、迁移脚本 | Phase 0-5 |
| **测试工程师** | 1 | 测试用例、自动化测试、性能测试 | Phase 1-5 |
| **DevOps** | 1 | CI/CD、K8s、监控、部署 | Phase 0-5 |

### 2.2 团队 A / 团队 B 分工

```
团队 A（后端开发A + 前端）          团队 B（后端开发B + DBA）
├── gewu-domain/session            ├── gewu-domain/workflow
├── gewu-domain/agent              ├── gewu-domain/sandbox
├── gewu-domain/tool               ├── gewu-gateway
├── gewu-interface (REST/SSE)      ├── gewu-infrastructure/mq
├── gewu-web (React)               ├── gewu-infrastructure/cache
└── 前端组件                       └── 数据库 DDL/迁移
```

---

## 3. 技术 Spike 验证（Phase 0）

> **目标**：在正式开发前验证 4 项关键技术风险，输出验证报告。

### 3.1 Spike 清单

| 编号 | Spike 项 | 验证内容 | 负责人 | 工期 | 产出 |
|------|---------|---------|--------|------|------|
| SP-01 | **OceanBase K8s 部署** | OB Operator 部署 3 节点集群；OBProxy 负载均衡；MySQL 兼容模式验证；DDL/DML 性能基准 | DBA | 3 天 | 部署脚本 + 性能基准报告 |
| SP-02 | **国密算法性能** | Bouncy Castle SM2/SM3/SM4 在鲲鹏 920 上的加解密性能；SM4-GCM vs SM4-CBC 性能对比；TLS 握手延迟 | 架构师 | 2 天 | 性能基准报告 |
| SP-03 | **信创环境兼容性** | iSulad 容器引擎在麒麟 V10 上的运行验证；Firecracker/gVisor 在 ARM64 上的启动时间；国产 JDK 兼容性 | DevOps | 3 天 | 兼容性测试报告 |
| SP-04 | **React Flow 性能** | 100+ 节点工作流设计器的渲染性能；拖拽交互流畅度；大数据量下的虚拟化方案 | 前端 | 2 天 | 性能测试报告 |

### 3.2 Spike 验收标准

| Spike | 通过标准 | 失败回退 |
|-------|---------|---------|
| SP-01 | OB 单节点 TPS > 2000；3 节点集群 RPO=0 | 降级为 MySQL 8.0 开发环境 |
| SP-02 | SM4-GCM 加密吞吐 > 100MB/s；TLS 握手 < 50ms | 使用硬件加速卡 |
| SP-03 | iSulad 容器启动 < 500ms；Firecracker ARM64 启动 < 5s | 降级为 Docker 开发环境 |
| SP-04 | 100 节点渲染 < 2s；拖拽帧率 > 30fps | 使用 Canvas 替代 SVG |

### 3.3 开发环境搭建

| 任务 | 负责人 | 工期 | 产出 |
|------|--------|------|------|
| Docker Compose 本地开发环境 | DevOps | 2 天 | docker-compose.yml（OB + DragonflyDB + RocketMQ + Nacos） |
| Maven 多模块项目骨架 | Tech Lead | 1 天 | 7 个 Java module + 依赖配置 |
| 代码规范 + Git 工作流 | 架构师 | 1 天 | CONTRIBUTING.md + .editorconfig + checkstyle 配置 |
| CI/CD 流水线搭建 | DevOps | 2 天 | Jenkins Pipeline（编译→测试→镜像→部署） |
| 数据库 DDL 初始化 | DBA | 1 天 | V1__init_schema.sql（32 张表） |

---

## 4. WBS 工作分解结构

### 4.1 Phase 1: 基础设施层（Sprint 1-2，4 周）

| WBS | 任务 | 负责人 | 工期 | 依赖 | 产出 |
|-----|------|--------|------|------|------|
| **1.1 公共模块** | | | | | |
| 1.1.1 | gewu-common: ULID 生成器、统一异常、响应封装 | 后端A | 3 天 | — | ULID 工具类 + 全局异常处理 |
| 1.1.2 | gewu-common: 国密工具类（SM2/SM3/SM4-GCM） | 后端A | 2 天 | SP-02 | SM2/SM3/SM4 工具类 |
| 1.1.3 | gewu-common: 审计切面（AOP 自动记录操作日志） | 后端A | 2 天 | — | @Auditable 注解 + 切面 |
| 1.1.4 | gewu-common: 逻辑删除 + 乐观锁拦截器 | 后端A | 1 天 | — | MyBatis-Plus 拦截器 |
| **1.2 权限模块** | | | | | |
| 1.2.1 | gewu-domain/user: User 聚合 + Repository | 后端A | 3 天 | 1.1.1 | User 领域模型 + JPA |
| 1.2.2 | gewu-domain/user: RBAC 权限模型（Role/Permission） | 后端A | 3 天 | 1.2.1 | RBAC 领域模型 |
| 1.2.3 | gewu-infrastructure/security: JWT 签发/验证（SM3withSM2） | 后端A | 3 天 | 1.1.2 | JwtTokenProvider |
| 1.2.4 | gewu-infrastructure/security: SM3 密码哈希（10000 次迭代） | 后端A | 1 天 | 1.1.2 | PasswordEncoder |
| 1.2.5 | gewu-interface: 认证 API（登录/刷新/登出） | 后端A | 3 天 | 1.2.3 | AuthController |
| **1.3 缓存 + 消息** | | | | | |
| 1.3.1 | gewu-infrastructure/cache: DragonflyDB 集成 + Redisson 配置 | 后端B | 2 天 | SP-01 | CacheConfig + RedissonClient |
| 1.3.2 | gewu-infrastructure/cache: 分布式锁实现 | 后端B | 2 天 | 1.3.1 | DistributedLockService |
| 1.3.3 | gewu-infrastructure/mq: RocketMQ 集成 + 事务消息 | 后端B | 3 天 | — | RocketMQConfig + TransactionProducer |
| 1.3.4 | gewu-infrastructure/mq: 领域事件发布/订阅框架 | 后端B | 2 天 | 1.3.3 | DomainEventPublisher |
| **1.4 数据库** | | | | | |
| 1.4.1 | OceanBase DDL 脚本（32 张表 + 索引） | DBA | 3 天 | SP-01 | V1__init_schema.sql |
| 1.4.2 | MyBatis-Plus 实体 + Mapper（全部 32 张表） | DBA + 后端B | 5 天 | 1.4.1 | 32 个 Entity + Mapper |
| 1.4.3 | Flyway 数据库版本管理集成 | DBA | 1 天 | 1.4.1 | FlywayConfig |
| **1.5 网关** | | | | | |
| 1.5.1 | Spring Cloud Gateway 基础路由配置 | 后端B | 2 天 | — | GatewayConfig |
| 1.5.2 | 国密 TLS Filter（SM2 证书 + SM4 加密） | 后端B | 3 天 | 1.1.2 | SmTlsFilter |
| 1.5.3 | JWT 认证 Filter + 限流 Filter | 后端B | 2 天 | 1.2.3 | AuthFilter + RateLimitFilter |
| **1.6 联调** | | | | | |
| 1.6.1 | 权限模块集成测试 | 后端A | 2 天 | 1.2.* | 集成测试报告 |
| 1.6.2 | 缓存 + MQ 集成测试 | 后端B | 2 天 | 1.3.* | 集成测试报告 |
| 1.6.3 | 网关端到端测试 | 后端B | 1 天 | 1.5.* | E2E 测试报告 |

### 4.2 Phase 2: 核心模块（Sprint 3-8，6 周）

| WBS | 任务 | 负责人 | 工期 | 依赖 | 产出 |
|-----|------|--------|------|------|------|
| **2.1 Session 模块** | | | | | |
| 2.1.1 | gewu-domain/session: Session 聚合 + 事件溯源 | 后端A | 5 天 | Phase 1 | SessionAggregate + EventStore |
| 2.1.2 | gewu-domain/session: SessionInput 三级管道（admit/promoteSteers/promoteNext） | 后端A | 3 天 | 2.1.1 | SessionInputService |
| 2.1.3 | gewu-domain/session: Part 表 + 消息分段 | 后端A | 2 天 | 2.1.1 | PartService |
| 2.1.4 | gewu-application/session: Command/Query 处理 | 后端A | 3 天 | 2.1.* | SessionCommandHandler |
| 2.1.5 | gewu-interface: Session REST API（CRUD + 列表 + 搜索） | 后端A | 3 天 | 2.1.4 | SessionController |
| **2.2 SSE 流式推送** | | | | | |
| 2.2.1 | SSE 端点实现（SseEmitter + 心跳保活） | 后端A | 3 天 | 2.1.1 | SseController |
| 2.2.2 | 14 种事件类型推送（MessageStarted/Completed/ToolInvoked 等） | 后端A | 3 天 | 2.2.1 | SseEventPublisher |
| 2.2.3 | SSE 断线重连（Last-Event-ID + 指数退避） | 后端A | 2 天 | 2.2.1 | 客户端重连逻辑 |
| **2.3 Agent 模块** | | | | | |
| 2.3.1 | gewu-domain/agent: Agent 聚合 + Tool 注册 | 后端A | 3 天 | Phase 1 | AgentAggregate |
| 2.3.2 | gewu-domain/agent: Agent 权限模型（allow/deny + 条件表达式） | 后端A | 3 天 | 2.3.1 | AgentPermissionService |
| 2.3.3 | gewu-infrastructure/llm: LLMClient 集成（通义/DeepSeek） | 后端B | 5 天 | Phase 1 | LlmClient + ModelRouter |
| 2.3.4 | gewu-interface: Agent REST API | 后端A | 2 天 | 2.3.* | AgentController |
| **2.4 沙箱模块** | | | | | |
| 2.4.1 | gewu-domain/sandbox: SandboxManager 接口 + 三级策略 | 后端B | 3 天 | Phase 1 | SandboxManager |
| 2.4.2 | gewu-infrastructure/sandbox: L3 iSulad 沙箱实现 | 后端B | 5 天 | SP-03, 2.4.1 | ISuladSandboxProvider |
| 2.4.3 | gewu-infrastructure/sandbox: L2 gVisor 沙箱实现 | 后端B | 3 天 | 2.4.1 | GVisorSandboxProvider |
| 2.4.4 | gewu-infrastructure/sandbox: 预热池 + 资源限制 | 后端B | 3 天 | 2.4.2 | WarmPool + ResourceLimiter |
| 2.4.5 | gewu-infrastructure/sandbox: 审计日志（result + log_hash） | 后端B | 2 天 | 2.4.2 | SandboxAuditService |
| 2.4.6 | gewu-interface: 沙箱 REST API | 后端B | 2 天 | 2.4.* | SandboxController |
| **2.5 前端基础** | | | | | |
| 2.5.1 | React 18 + Ant Design 项目骨架 | 前端 | 2 天 | — | 前端项目结构 |
| 2.5.2 | 登录/注册页面 + JWT 管理 | 前端 | 3 天 | 1.2.5 | LoginPage + AuthContext |
| 2.5.3 | 会话列表 + 会话详情页面 | 前端 | 5 天 | 2.1.5 | SessionListPage + SessionDetailPage |
| 2.5.4 | SSE 客户端 + 消息流式渲染 | 前端 | 3 天 | 2.2.* | SseClient + MessageStream |
| 2.5.5 | Agent 配置页面 + 工具管理 | 前端 | 3 天 | 2.3.4 | AgentConfigPage |
| **2.6 联调** | | | | | |
| 2.6.1 | Session 模块端到端测试 | 后端A + 前端 | 3 天 | 2.1.* + 2.5.3 | E2E 测试 |
| 2.6.2 | Agent + 沙箱联调 | 后端A + 后端B | 3 天 | 2.3.* + 2.4.* | 联调测试报告 |
| 2.6.3 | SSE 流式推送端到端测试 | 后端A + 前端 | 2 天 | 2.2.* + 2.5.4 | SSE E2E 测试 |

### 4.3 Phase 3: 工作流模块（Sprint 9-12，4 周）

| WBS | 任务 | 负责人 | 工期 | 依赖 | 产出 |
|-----|------|--------|------|------|------|
| **3.1 工作流引擎** | | | | | |
| 3.1.1 | 6-状态工作流状态机 + 8-状态节点状态机 | 后端B | 5 天 | Phase 1 | WorkflowStateMachine |
| 3.1.2 | 并行分支 + ALL_COMPLETE 同步 | 后端B | 3 天 | 3.1.1 | ParallelFlowHandler |
| 3.1.3 | 超时检测 + 多级提醒（4h/2h） | 后端B | 2 天 | 3.1.1 | TimeoutChecker |
| 3.1.4 | 事件幂等性（原子状态更新 + 分布式锁） | 后端B | 2 天 | 3.1.1 | IdempotentEventHandler |
| 3.1.5 | Saga 事务补偿（工作流回退） | 后端B | 3 天 | 3.1.1 | SagaCompensationService |
| **3.2 工作流 API** | | | | | |
| 3.2.1 | 工作流定义 CRUD API（5 端点） | 后端B | 3 天 | 3.1.1 | WorkflowDefinitionController |
| 3.2.2 | 工作流实例 API（8 端点：启动/暂停/恢复/取消/查询） | 后端B | 3 天 | 3.1.* | WorkflowInstanceController |
| 3.2.3 | 节点操作 API（5 端点：完成/驳回/转交/加签） | 后端B | 2 天 | 3.1.* | NodeOperationController |
| 3.2.4 | 通知 + 权限 + 审计 API（4 端点） | 后端B | 2 天 | 3.1.* | NotificationController |
| **3.3 工作流 UI** | | | | | |
| 3.3.1 | React Flow 可视化设计器（节点/连线/属性面板） | 前端 | 5 天 | SP-04 | WorkflowDesigner |
| 3.3.2 | 工作流定义列表 + 创建/编辑页面 | 前端 | 3 天 | 3.3.1 | WorkflowListPage |
| 3.3.3 | 工作流实例详情 + 执行监控页面 | 前端 | 3 天 | 3.2.2 | WorkflowInstancePage |
| 3.3.4 | 通知中心 + 待办任务列表 | 前端 | 2 天 | 3.2.4 | NotificationCenter |
| **3.4 联调** | | | | | |
| 3.4.1 | 工作流引擎单元测试（状态机 + 并行 + 超时） | 后端B | 3 天 | 3.1.* | 单元测试报告 |
| 3.4.2 | 工作流端到端测试 | 后端B + 前端 | 3 天 | 3.2.* + 3.3.* | E2E 测试报告 |

### 4.4 Phase 4: 集成测试（Sprint 13-15，3 周）

| WBS | 任务 | 负责人 | 工期 | 依赖 | 产出 |
|-----|------|--------|------|------|------|
| **4.1 全链路测试** | | | | | |
| 4.1.1 | 全链路集成测试（用户→项目→会话→Agent→工具→沙箱） | 测试 | 5 天 | Phase 1-3 | 集成测试报告 |
| 4.1.2 | 工作流全链路测试（定义→实例→节点→通知→审计） | 测试 | 3 天 | Phase 3 | 工作流测试报告 |
| **4.2 性能测试** | | | | | |
| 4.2.1 | API 性能基准测试（核心 API P95 < 200ms） | 测试 + DevOps | 3 天 | 4.1.1 | 性能测试报告 |
| 4.2.2 | 数据库压力测试（OceanBase TPS/慢查询） | DBA + 测试 | 2 天 | 4.1.1 | DB 性能报告 |
| 4.2.3 | SSE 并发测试（100 并发 SSE 连接） | 测试 | 2 天 | 4.1.1 | SSE 性能报告 |
| 4.2.4 | 沙箱启动性能测试（L1/L2/L3 冷启动） | 测试 + DevOps | 2 天 | 4.1.1 | 沙箱性能报告 |
| **4.3 安全测试** | | | | | |
| 4.3.1 | 渗透测试（OWASP Top 10） | 测试 | 3 天 | 4.1.1 | 渗透测试报告 |
| 4.3.2 | 国密合规验证（SM2/SM3/SM4 全链路加密） | 架构师 | 2 天 | 4.1.1 | 国密合规报告 |
| 4.3.3 | 等保 2.0 三级预测评 | 架构师 + 测试 | 3 天 | 4.3.1 | 预测评报告 |
| **4.4 信创测试** | | | | | |
| 4.4.1 | 信创环境全量测试（鲲鹏 + 麒麟 + iSulad + OB） | DevOps + 测试 | 5 天 | SP-03 | 信创兼容性报告 |
| 4.4.2 | 多数据库兼容测试（OceanBase + 达梦） | DBA + 测试 | 3 天 | 4.1.1 | 多 DB 测试报告 |

### 4.5 Phase 5: 迁移上线（Sprint 16-18+，3-4 周）

| WBS | 任务 | 负责人 | 工期 | 依赖 | 产出 |
|-----|------|--------|------|------|------|
| **5.1 数据迁移** | | | | | |
| 5.1.1 | SQLite → OceanBase 数据迁移脚本 | DBA | 3 天 | Phase 4 | 迁移脚本 + 验证报告 |
| 5.1.2 | OpenCode 兼容层（6 个月过渡期 API） | 后端A | 5 天 | Phase 4 | CompatibilityLayer |
| 5.1.3 | 迁移验证（全量数据对比 + 回归测试） | 测试 | 3 天 | 5.1.1 | 迁移验证报告 |
| **5.2 生产部署** | | | | | |
| 5.2.1 | 生产环境 K8s 集群部署 | DevOps | 3 天 | Phase 4 | 生产集群 |
| 5.2.2 | 蓝绿部署 + 金丝雀发布 | DevOps | 2 天 | 5.2.1 | 部署脚本 |
| 5.2.3 | 监控告警配置（Nightingale + VictoriaMetrics） | DevOps | 2 天 | 5.2.1 | 监控面板 + 告警规则 |
| 5.2.4 | 备份恢复策略配置 + 演练 | DBA + DevOps | 2 天 | 5.2.1 | 备份策略 + 演练报告 |
| **5.3 上线** | | | | | |
| 5.3.1 | 预发布环境验证 | 测试 + DevOps | 2 天 | 5.2.* | 预发布验证报告 |
| 5.3.2 | 生产上线（金丝雀 5% → 20% → 100%） | 全团队 | 2 天 | 5.3.1 | 上线完成 |
| 5.3.3 | 上线后监控 + 问题修复 | 全团队 | 5 天 | 5.3.2 | 稳定性报告 |

---

## 5. Sprint 详细计划

### Sprint 0（W1-W2）: 准备期

| 日 | 任务 | 负责人 |
|----|------|--------|
| D1-D3 | SP-01: OceanBase K8s 部署验证 | DBA |
| D1-D2 | SP-02: 国密算法性能基准 | 架构师 |
| D1-D3 | SP-03: 信创环境兼容性验证 | DevOps |
| D1-D2 | SP-04: React Flow 性能验证 | 前端 |
| D2-D3 | Docker Compose 本地开发环境 | DevOps |
| D3-D4 | Maven 多模块项目骨架 | Tech Lead |
| D4-D5 | 代码规范 + Git 工作流 | 架构师 |
| D5-D7 | CI/CD 流水线搭建 | DevOps |
| D7-D8 | 数据库 DDL 初始化（32 张表） | DBA |
| D8-D10 | Spike 报告汇总 + Go/No-Go 决策 | 全团队 |

**Sprint 0 交付物**：
- [ ] 4 份 Spike 验证报告
- [ ] Docker Compose 开发环境
- [ ] Maven 项目骨架（7 模块）
- [ ] CI/CD 流水线
- [ ] 数据库 DDL V1
- [ ] Go/No-Go 决策会议纪要

### Sprint 1-2（W3-W6）: 基础设施层

**Sprint 1（W3-W4）**:
- 后端A: 1.1.* (公共模块) + 1.2.1-1.2.2 (User/RBAC 领域模型)
- 后端B: 1.3.1-1.3.2 (缓存) + 1.4.1-1.4.2 (DB DDL + Entity)
- DBA: 1.4.1-1.4.3 (OceanBase DDL + Flyway)
- 前端: 2.5.1-2.5.2 (React 骨架 + 登录页)

**Sprint 2（W5-W6）**:
- 后端A: 1.2.3-1.2.5 (JWT + 认证 API) + 1.6.1 (权限集成测试)
- 后端B: 1.3.3-1.3.4 (MQ) + 1.5.* (网关) + 1.6.2-1.6.3 (联调)
- DBA: 1.4.2 续 (Mapper 完成)
- 测试: 编写 Phase 1 测试用例

**Sprint 1-2 交付物**：
- [ ] gewu-common 公共模块（ULID/国密/审计/逻辑删除）
- [ ] 权限模块（User/Role/Permission + JWT + 认证 API）
- [ ] 缓存模块（DragonflyDB + 分布式锁）
- [ ] 消息模块（RocketMQ + 领域事件框架）
- [ ] 网关（Spring Cloud Gateway + 国密 TLS + 认证 + 限流）
- [ ] 32 张表 DDL + Entity + Mapper
- [ ] 前端登录页 + 基础布局
- [ ] Phase 1 集成测试报告

### Sprint 3-8（W7-W18）: 核心模块

**Sprint 3-4（W7-W10）**: Session + SSE
- 后端A: 2.1.* (Session 聚合 + 事件溯源 + Part + API)
- 后端A: 2.2.* (SSE 流式推送)
- 前端: 2.5.3-2.5.4 (会话页面 + SSE 客户端)

**Sprint 5-6（W11-W14）**: Agent + 沙箱
- 后端A: 2.3.* (Agent 聚合 + 权限 + API)
- 后端B: 2.3.3 (LLMClient) + 2.4.* (沙箱三级 + 预热池 + 审计)
- 前端: 2.5.5 (Agent 配置页)

**Sprint 7-8（W15-W18）**: 核心联调 + 优化
- 全团队: 2.6.* (端到端联调)
- 测试: Phase 2 测试用例 + 执行
- 性能优化: 慢查询优化 + 缓存策略调优

**Sprint 3-8 交付物**：
- [ ] Session 模块（事件溯源 + 三级管道 + Part 分段）
- [ ] SSE 流式推送（14 种事件 + 断线重连）
- [ ] Agent 模块（聚合 + 权限 + LLM 集成）
- [ ] 沙箱模块（三级沙箱 + 预热池 + 审计）
- [ ] 前端：会话列表/详情 + SSE 流式 + Agent 配置
- [ ] Phase 2 集成测试报告

### Sprint 9-12（W19-W26）: 工作流模块

**Sprint 9-10（W19-W22）**: 工作流引擎 + API
- 后端B: 3.1.* (状态机 + 并行 + 超时 + 幂等 + Saga)
- 后端B: 3.2.* (22 个 API 端点)

**Sprint 11-12（W23-W26）**: 工作流 UI + 联调
- 前端: 3.3.* (React Flow 设计器 + 列表/详情/通知)
- 全团队: 3.4.* (联调 + 端到端测试)

**Sprint 9-12 交付物**：
- [ ] 工作流引擎（6+8 状态机 + 并行 + 超时 + 幂等 + Saga）
- [ ] 工作流 API（22 端点）
- [ ] 工作流 UI（React Flow 设计器 + 4 个页面）
- [ ] Phase 3 测试报告

### Sprint 13-15（W27-W33）: 集成测试

- 测试: 4.1.* (全链路) + 4.2.* (性能) + 4.3.* (安全)
- DevOps: 4.4.* (信创测试)
- DBA: 数据库性能调优

**Sprint 13-15 交付物**：
- [ ] 全链路集成测试报告
- [ ] 性能测试报告（核心 API P95 < 200ms）
- [ ] 安全测试报告（渗透 + 国密 + 等保预测评）
- [ ] 信创兼容性报告

### Sprint 16-18+（W34-W40）: 迁移上线

- DBA: 5.1.* (数据迁移)
- DevOps: 5.2.* (生产部署)
- 全团队: 5.3.* (上线)

**Sprint 16-18 交付物**：
- [ ] 数据迁移脚本 + 验证报告
- [ ] OpenCode 兼容层
- [ ] 生产环境 K8s 集群
- [ ] 监控告警 + 备份恢复
- [ ] 上线完成 + 稳定性报告

---

## 6. 里程碑与交付物

| 里程碑 | 时间 | 关键交付物 | 验收标准 |
|--------|------|-----------|---------|
| **M0: Spike 通过** | W2 | 4 份验证报告 + 开发环境 | 所有 Spike 通过验收标准 |
| **M1: 基础设施就绪** | W6 | 权限/缓存/MQ/网关/DB | 认证 API 可用 + 网关路由通 |
| **M2: 核心功能 Alpha** | W12 | Session + Agent + SSE | 会话创建→消息发送→SSE 推送端到端 |
| **M3: 核心功能 Beta** | W18 | 沙箱 + 核心联调 | Agent 调用工具→沙箱执行→结果返回 |
| **M4: 工作流 Beta** | W26 | 工作流引擎 + UI | 流程定义→实例启动→节点流转→完成 |
| **M5: RC 候选版** | W33 | 全链路测试通过 | 性能/安全/信创测试全部通过 |
| **M6: 生产上线** | W40 | 生产环境运行 | 金丝雀发布 100% + 7 天稳定运行 |

---

## 7. 风险管理

### 7.1 风险登记册

| 编号 | 风险 | 概率 | 影响 | 等级 | 缓解措施 | 负责人 |
|------|------|------|------|------|---------|--------|
| R-01 | OceanBase 信创环境部署失败 | 中 | 高 | 🔴 | SP-01 提前验证；回退 MySQL 8.0 | DBA |
| R-02 | 国密性能不达标（SM4-GCM < 100MB/s） | 低 | 高 | 🟡 | SP-02 提前验证；硬件加速卡备选 | 架构师 |
| R-03 | 龙芯 LoongArch 适配延期 | 中 | 中 | 🟡 | 降级为 iSulad + gVisor；龙芯 Phase 2 | DevOps |
| R-04 | iSulad 生态兼容性问题 | 中 | 中 | 🟡 | 开发环境保留 Docker；生产 iSulad | DevOps |
| R-05 | 事件溯源性能瓶颈 | 中 | 高 | 🔴 | 投影表预聚合；CQRS 读写分离 | Tech Lead |
| R-06 | 沙箱冷启动延迟超标 | 中 | 中 | 🟡 | 预热池 + 虚拟线程自动补池 | 后端B |
| R-07 | React Flow 100+ 节点卡顿 | 低 | 中 | 🟢 | SP-04 提前验证；Canvas 虚拟化备选 | 前端 |
| R-08 | 工作流分布式并发竞态 | 中 | 高 | 🔴 | Redis 分布式锁 + 原子状态更新 | 后端B |
| R-09 | 等保测评不通过 | 低 | 高 | 🟡 | Phase 4 预测评；提前修复不合规项 | 架构师 |
| R-10 | 数据迁移丢失/损坏 | 中 | 高 | 🔴 | 全量校验脚本；回滚方案；双写过渡 | DBA |

### 7.2 风险应对时间线

```
W1-W2:  SP-01~04 验证 → 消除 R-01/R-02/R-03/R-07
W7-W8:  事件溯源性能测试 → 消除 R-05
W11-12: 沙箱性能测试 → 消除 R-06
W19-20: 工作流并发测试 → 消除 R-08
W27-29: 等保预测评 → 消除 R-09
W34-35: 迁移演练 → 消除 R-10
```

---

## 8. 质量门禁

### 8.1 每个 Sprint 的质量门禁

| 门禁项 | 标准 | 检查方式 |
|--------|------|---------|
| 单元测试覆盖率 | 领域层 100%，应用层 90%，接口层 80% | JaCoCo |
| 代码审查 | 所有 MR 至少 1 人审查 | GitLab MR |
| 静态分析 | 0 Blocker，0 Critical | SonarQube |
| 集成测试 | 核心路径 100% 通过 | Testcontainers |
| API 文档 | 所有端点有 OpenAPI 文档 | Swagger |
| 性能基准 | 核心 API P95 < 200ms | JMeter |

### 8.2 每个 Phase 的质量门禁

| Phase | 额外门禁 |
|-------|---------|
| Phase 1 完成 | 权限模块通过安全审查；国密加密全链路验证 |
| Phase 2 完成 | SSE 100 并发测试通过；沙箱隔离验证 |
| Phase 3 完成 | 工作流状态机 100% 分支覆盖；幂等性验证 |
| Phase 4 完成 | 全链路测试 100% 通过；等保预测评通过 |
| Phase 5 完成 | 数据迁移 100% 行匹配；7 天稳定运行 |

---

## 9. CI/CD 集成

### 9.1 流水线阶段

```
代码提交 → 代码扫描(SonarQube) → 单元测试 → 集成测试(Testcontainers)
    → Maven 构建 → 镜像构建 → 镜像扫描(Trivy) → 数据库迁移验证
    → 部署(Dev) → 冒烟测试 → 部署(Staging) → E2E 测试
    → 部署(Production, 金丝雀)
```

### 9.2 环境策略

| 环境 | 用途 | 数据库 | 部署方式 |
|------|------|--------|---------|
| **Dev** | 开发联调 | OceanBase 单节点 | Docker Compose |
| **Staging** | 集成测试 | OceanBase 3 节点 | K8s 单集群 |
| **Pre-prod** | 预发布验证 | OceanBase 3 节点（同生产配置） | K8s 单集群 |
| **Production** | 生产环境 | OceanBase 3 节点 + OBProxy | K8s 多集群 + 蓝绿 |

---

## 10. 附录

### 10.1 参考文档

| 文档 | 版本 | 说明 |
|------|------|------|
| 20-unified-prd.md | V1.2 | 统一 PRD（69 用户故事） |
| 21-unified-architecture.md | V1.2 | 统一架构（ADR-023 网关选型） |
| 22-unified-db-schema.md | V1.2 | 统一数据库（32 张表） |
| 23-unified-api-spec.md | V1.1 | 统一 API 规范（107 端点） |
| 24-unified-security.md | V1.1 | 统一安全（SM4-GCM） |
| 25-unified-deployment.md | V1.2 | 统一部署（容量规划） |
| 26-migration-guide.md | V1.0 | 迁移指南 |
| 27-agent-sandbox-design.md | V1.1 | 沙箱设计（三级 + 兼容性） |
| 28-workflow-engine-design.md | V1.1 | 工作流引擎（幂等性） |
| 30-workflow-api-design.md | V1.1 | 工作流 API（22 端点） |
| 31-workflow-ui-design.md | V1.0 | 工作流 UI（React Flow） |
| 32-test-strategy.md | V1.0 | 测试策略 |

### 10.2 术语表

| 术语 | 说明 |
|------|------|
| WBS | Work Breakdown Structure，工作分解结构 |
| Spike | 技术验证探针，用于降低技术风险 |
| Go/No-Go | 阶段门决策会议 |
| RC | Release Candidate，发布候选版 |
| E2E | End-to-End，端到端测试 |
| DDD | Domain-Driven Design，领域驱动设计 |
| CQRS | Command Query Responsibility Segregation |

---

**文档结束**
