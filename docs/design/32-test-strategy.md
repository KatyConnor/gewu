# 格物平台 测试策略文档 V1.0

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 测试策略文档 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 22-unified-db-schema.md, 23-unified-api-spec.md, 24-unified-security.md |
| 覆盖范围 | 格物平台全系统 — Session/Agent/Tool/Workflow/Sandbox 核心业务模块 |

---

## 目录

1. [测试总纲](#1-测试总纲)
2. [测试分层策略](#2-测试分层策略)
3. [核心业务逻辑测试](#3-核心业务逻辑测试)
4. [API 集成测试](#4-api-集成测试)
5. [数据库测试](#5-数据库测试)
6. [安全测试](#6-安全测试)
7. [性能测试](#7-性能测试)
8. [测试自动化与 CI/CD](#8-测试自动化与-cicd)
9. [测试环境管理](#9-测试环境管理)
10. [质量门禁](#10-质量门禁)
11. [与统一文档的交叉引用](#11-与统一文档的交叉引用)

---

## 1. 测试总纲

### 1.1 测试目标

| 目标 | 量化指标 |
|------|---------|
| **功能性** | 核心业务逻辑测试覆盖率 ≥ 90% |
| **可靠性** | P99 API 响应 ≤ 2s，故障恢复时间 ≤ 5min |
| **安全性** | 无高危漏洞通过 SAST/DAST |
| **兼容性** | 信创环境（麒麟 V10/统信 UOS × 鲲鹏/飞腾/龙芯）全部通过 |
| **可维护性** | 新增代码测试覆盖率 ≥ 80%，圈复杂度 ≤ 10 |

### 1.2 测试金字塔

```
         /     E2E 测试 (5%)      \
        /      集成测试 (25%)       \
       /       单元测试 (70%)        \
      /   ─────────────────────      \
     /   Session | Agent | Tool | WF  \
```

| 层级 | 占比 | 运行时间 | 策略 |
|------|------|---------|------|
| **单元测试** | 70% | < 5min | JUnit 5 + Mockito，隔离基础设施依赖 |
| **集成测试** | 25% | < 15min | Testcontainers + 真实 DB/MQ 集成 |
| **E2E 测试** | 5% | < 30min | 全链路验证，关键用户场景 |

### 1.3 测试原则

| 原则 | 说明 |
|------|------|
| **测试左移** | 需求阶段定义验收测试，编码阶段同步编写测试 |
| **AAA 模式** | Arrange（准备数据）→ Act（执行操作）→ Assert（验证结果） |
| **单一关注点** | 每个测试用例只验证一个行为 |
| **避免重复** | 不重复实现逻辑到测试中，测试数据用工厂方法 |
| **隔离性** | 测试间无依赖，可独立运行 |

---

## 2. 测试分层策略

### 2.1 单元测试

#### 技术栈

| 工具 | 用途 |
|------|------|
| JUnit 5 | 测试框架 |
| Mockito 5 | Mock 框架 |
| AssertJ | 断言库 |
| JaCoCo | 覆盖率收集 |
| ArchUnit | 架构规则验证 |

#### 覆盖目标

| 模块 | 类覆盖率 | 方法覆盖率 | 行覆盖率 |
|------|---------|---------|---------|
| 领域层 (Domain) | 100% | 95% | 90% |
| 应用层 (Application) | 100% | 90% | 85% |
| 接口层 (Interface) | 90% | 80% | 75% |
| 基础设施层 (Infrastructure) | 80% | 70% | 65% |
| **总体目标** | **90%** | **85%** | **80%** |

#### 需要 Mock 的场景

| 依赖 | Mock 策略 |
|------|----------|
| 数据库 (OceanBase) | Mock Repository 接口 |
| 缓存 (DragonflyDB) | Mock CacheClient |
| 消息队列 (RocketMQ) | Mock EventPublisher |
| LLM 客户端 | Mock 流式响应 |
| 沙箱运行时 | Mock SandboxRuntime 接口 |

#### 不需要 Mock 的场景

| 场景 | 原因 |
|------|------|
| 纯逻辑计算 | 无外部依赖 |
| Schema 验证 | 使用真实 Validator |
| DDD 聚合根状态机 | 纯内存状态转换 |

### 2.2 集成测试

#### 技术栈

| 工具 | 用途 |
|------|------|
| Testcontainers | 容器化集成测试环境 |
| Spring Boot Test | Spring 上下文测试 |
| MyBatis-Plus Test | 数据库 CRUD 测试 |
| RocketMQ Test | 消息收发测试 |

#### 集成测试范围

| 测试类型 | 依赖 | 说明 |
|---------|------|------|
| **Repository 测试** | OceanBase (Testcontainers) | 验证 DDL + CRUD + 复杂查询 |
| **Service 集成测试** | OceanBase + RocketMQ | 验证事务 + 事件发布 |
| **API 集成测试** | 完整 Spring 上下文 | MockMvc 验证请求/响应 |
| **消息集成测试** | RocketMQ (Testcontainers) | 验证消息收发 + 死信队列 |

#### Testcontainers 配置

```java
@Testcontainers
@SpringBootTest
class GewuIntegrationTestBase {
    
    // OceanBase 兼容 MySQL 8.0
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("gewu_test")
        .withUsername("test")
        .withPassword("test");
    
    // RocketMQ
    @Container
    static GenericContainer<?> rocketmq = new GenericContainer<>("apache/rocketmq:5.1.4")
        .withExposedPorts(9876, 10911);
    
    // DragonflyDB
    @Container
    static GenericContainer<?> dragonfly = new GenericContainer<>("docker.dragonflydb.io/dragonflydb/dragonfly:v1.27.1")
        .withExposedPorts(6379);
}
```

### 2.3 E2E 测试

| 技术 | 说明 |
|------|------|
| **Cypress / Playwright** | Web UI 自动化测试 |
| **Postman / Newman** | API 链式测试 |
| **自定义测试客户端** | Java HttpClient 全链路验证 |

#### E2E 测试场景

| 场景 | 流程 | 优先级 |
|------|------|--------|
| **用户登录→创建会话→发送消息→AI 响应** | 端到端核心链路 | P0 |
| **创建 Agent→配置工具→执行 Agent→查看结果** | Agent 全生命周期 | P0 |
| **创建工作流定义→启动实例→完成节点→审核节点→完成流程** | 工作流全链路 | P1 |
| **沙箱执行代码→查看结果→查看审计日志** | 沙箱全链路 | P1 |

---

## 3. 核心业务逻辑测试

### 3.1 Session 会话模块测试

#### 领域层单元测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `SessionAggregateTest.admitNewMessage` | admit 新消息（steer 模式） | 生成 admitted 事件，状态推进 |
| `SessionAggregateTest.admitQueueMessage` | admit 新消息（queue 模式） | 消息入队，不立即推进 |
| `SessionAggregateTest.promoteSteers` | promote 排队的 steer 消息 | 按序 promote，忽略已 promote 的 |
| `SessionAggregateTest.promoteNextQueued` | 空闲时 promote 队列消息 | 首个 queue 消息转为 active |
| `SessionAggregateTest.rebuildFromEvents` | 从事件流重建会话投影 | 投影状态与原始一致 |
| `SessionAggregateTest.compressContext` | 上下文长度超限时压缩 | 压缩后历史摘要替换原始内容 |
| `SessionAggregateTest.archiveSession` | 归档会话 | 状态变为 ARCHIVED |
| `SessionAggregateTest.concurrencyGuard` | 并发 admit 同 session | 序列号自增，无冲突 |

#### Application Service 测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `SessionAppServiceTest.sendMessage_fullFlow` | 完整消息发送流程（含权限检查） | 消息持久化 + 事件发布 + SSE 推送 |
| `SessionAppServiceTest.sendMessage_permissionDenied` | 权限拒绝场景 | 返回 403，不执行 AI 调用 |
| `SessionAppServiceTest.sendMessage_llmTimeout` | LLM 调用超时 | 返回超时错误，事件标记失败 |

### 3.2 Agent 模块测试

#### 领域层单元测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `AgentAggregateTest.createAgent` | 创建 Agent（含模型+工具配置） | Agent 状态为 DRAFT |
| `AgentAggregateTest.activateAgent` | 激活 Agent | 状态变为 ACTIVE |
| `AgentAggregateTest.addTool` | 注册工具到 Agent | 工具列表中包含新增工具 |
| `AgentAggregateTest.removeTool` | 移除 Agent 工具 | 工具列表中不再包含 |
| `AgentAggregateTest.validateToolSchema` | 工具输入校验 | Schema 不匹配时抛出验证错误 |

#### Tool 执行测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `ToolExecutionServiceTest.executeTool_allow` | 执行允许的工具（含沙箱隔离） | 返回执行结果 |
| `ToolExecutionServiceTest.executeTool_deny` | 执行拒绝的工具 | 抛出 BlockedError |
| `ToolExecutionServiceTest.executeTool_ask_thenAllow` | 执行需询问的工具→用户允许 | 用户确认后执行 |
| `ToolExecutionServiceTest.executeTool_ask_thenDeny` | 执行需询问的工具→用户拒绝 | 不执行，返回拒绝 |
| `ToolExecutionServiceTest.executeTool_30sTimeout` | 工具执行超时（默认 30s） | 超时后截断结果 |
| `ToolExecutionServiceTest.executeTool_resultSizeLimit` | 工具返回结果超限 | 截断或分页返回 |

### 3.3 工作流模块测试

#### 状态机测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `WorkflowStateMachineTest.transitionValid` | 合法状态转换链 | 每个转换成功 |
| `WorkflowStateMachineTest.transitionInvalid` | 非法状态转换（如 COMPLETED→RUNNING） | 抛出 IllegalStateException |
| `WorkflowStateMachineTest.allTransitions` | 遍历全部 6 个状态 × 合法转移 | 通过状态矩阵验证 |

#### 工作流实例测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `WorkflowInstanceServiceTest.startWorkflow` | 启动工作流实例 | 实例 RUNNING，首节点 PENDING |
| `WorkflowInstanceServiceTest.completeNode` | 完成当前节点 | 节点 COMPLETED，下一节点 IN_PROGRESS |
| `WorkflowInstanceServiceTest.reviewNode_approved` | 审核通过 | 节点 COMPLETED，进入下一节点 |
| `WorkflowInstanceServiceTest.reviewNode_rejected` | 审核驳回 | 节点 REJECTED，回退到上一节点 |
| `WorkflowInstanceServiceTest.pauseAndResume` | 暂停→恢复工作流 | 状态 PAUSED→RUNNING |
| `WorkflowInstanceServiceTest.parallelBranchExecution` | 并行分支全部完成后推进 | 所有分支 COMPLETED 后主流程继续 |
| `WorkflowInstanceServiceTest.parallelBranchPartial` | 并行分支部分完成后超时 | 超时分支标记 TIMEOUT，其他分支继续 |
| `WorkflowInstanceServiceTest.timeoutDetection` | 节点超时检测 | 节点标记 TIMEOUT, 触发超时通知 |
| `WorkflowInstanceServiceTest.rollbackNode` | 回退节点 | 回退到指定节点，中间节点重置 |
| `WorkflowInstanceServiceTest.cancelInstance` | 取消工作流实例 | 状态 CANCELLED，未完成节点标记 SKIPPED |

### 3.4 沙箱模块测试

| 测试用例 | 场景 | 预期 |
|---------|------|------|
| `SandboxManagerTest.createSandbox_L1` | 创建 L1 Firecracker 沙箱 | 沙箱启动，资源限制生效 |
| `SandboxManagerTest.createSandbox_L2` | 创建 L2 gVisor 沙箱 | 沙箱启动，系统调用拦截 |
| `SandboxManagerTest.createSandbox_L3` | 创建 L3 iSulad 沙箱 | 沙箱快速启动 |
| `SandboxManagerTest.resourceLimit_cpu` | CPU 资源限制生效 | 沙箱内进程 CPU 不超限 |
| `SandboxManagerTest.resourceLimit_memory` | 内存资源限制生效 | 沙箱内进程内存不超限 |
| `SandboxManagerTest.networkIsolation` | 网络隔离（默认禁止联网） | SSH/HTTP 请求被拦截 |
| `SandboxManagerTest.executeCode` | 在沙箱内执行代码 | 返回正确执行结果 |
| `SandboxManagerTest.timeoutKill` | 超时后自动终止沙箱 | 沙箱进程被终止 |
| `SandboxPoolManagerTest.warmupPool` | 预热池维护 | 池内始终有 >= minIdle 的沙箱 |
| `SandboxPoolManagerTest.reuseSandbox` | 沙箱复用 | 复用后环境隔离，无状态残留 |
| `SandboxAuditTest.executionAuditLog` | 沙箱执行审计日志 | 完整记录执行 ID/IP/时间/结果 |

---

## 4. API 集成测试

### 4.1 通用验证规则

每个 API 端点测试覆盖：

| 维度 | 测试内容 |
|------|---------|
| **正常流程** | 200/201 成功响应，返回结构符合规范 |
| **认证失败** | 无 Token / 过期 Token / 无效 Token → 401 |
| **权限不足** | 角色无对应权限 → 403 |
| **参数校验** | 必填项缺失 / 类型错误 / 长度超限 → 400 |
| **资源不存在** | 无效 ID → 404 |
| **分页查询** | 默认值 / 边界值 / 排序 → 正确分页数据 |

### 4.2 核心 API 测试矩阵

| API 模块 | 端点数 | 正常测试 | 异常测试 | 边界测试 | 测试方法 |
|---------|--------|---------|---------|---------|---------|
| 用户管理 | 17 | 17 | 34 | 10 | MockMvc |
| 会话管理 | 12 | 12 | 24 | 8 | MockMvc |
| 消息管理 | 6 | 6 | 12 | 6 | MockMvc + SSE |
| Agent 系统 | 15 | 15 | 30 | 10 | MockMvc |
| **工作流 API** | **22** | **22** | **44** | **15** | MockMvc + Testcontainers |
| **沙箱 API** | **12** | **12** | **24** | **10** | MockMvc |

### 4.3 SSE 推送测试

```java
@Test
void testSseMessagePush() throws Exception {
    // 创建会话
    String sessionId = createSession();
    
    // 建立 SSE 连接
    StepVerifier.create(
        webTestClient.get()
            .uri("/api/v1/events/stream")
            .header("Authorization", "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class)
            .getResponseBody()
            .timeout(Duration.ofSeconds(30))
    )
    // 发送消息应触发 SSE 事件
    .expectNextMatches(event -> event.contains("session.message"))
    .verifyComplete();
}
```

---

## 5. 数据库测试

### 5.1 DDL 验证

| 测试项 | 验证方法 |
|--------|---------|
| 表结构正确性 | Flyway migrate + SELECT * FROM information_schema |
| 索引存在性 | SHOW INDEX FROM 检查关键查询索引 |
| FK 约束 | INSERT 无效引用预期失败 |
| ULID 主键 | 插入 ULID 格式数据验证 |
| JSON 字段 | JSON_VALID() 函数验证 |

### 5.2 数据迁移验证

| 测试项 | 场景 |
|--------|------|
| 增量迁移 | 新增数据→迁移→目标表与源表一致 |
| 全量迁移 | 10 万条数据→迁移→SELECT COUNT(*) 一致 |
| 迁移回滚 | Flyway undo → 表结构恢复 |
| 数据完整性 | 随机抽样 100 条，逐字段对比 |

---

## 6. 安全测试

### 6.1 安全测试清单

| 测试类型 | 工具 | 频率 | 门禁标准 |
|---------|------|------|---------|
| SAST | SonarQube | 每次提交 | 无 Critical/Blocker 问题 |
| DAST | OWASP ZAP | 每周 | 无高风险告警 |
| 依赖扫描 | OWASP Dependency-Check | 每次构建 | CVSS ≥ 7.0 阻断构建 |
| 容器扫描 | Trivy | 每次构建 | 无 CRITICAL/HIGH 漏洞 |
| 密钥扫描 | GitLeaks | 每次提交 | 无敏感信息泄露 |
| 国密合规 | 国密局标准测试 | 每次发布 | 全部 SM2/SM3/SM4 测试通过 |

### 6.2 渗透测试用例

| 场景 | 测试方法 |
|------|---------|
| SQL 注入 | `' OR 1=1 --`, `'; DROP TABLE users --` |
| XSS | `<script>alert(1)</script>` 注入消息内容 |
| CSRF | 跨站请求伪造验证 SameSite Cookie + Token |
| JWT 伪造 | 修改 payload / 使用弱密钥签名 |
| 越权访问 | 用户 A 的 Token 访问用户 B 的资源 |
| 沙箱逃逸 | 尝试从沙箱内访问宿主系统文件 |
| 国密算法 | SM2/SM3/SM4 已知攻击向量测试 |

---

## 7. 性能测试

### 7.1 性能目标 (SLA)

| 指标 | 目标 | 测量方法 |
|------|------|---------|
| P50 API 延迟 | < 200ms | Prometheus + Grafana |
| P95 API 延迟 | < 500ms | Prometheus + Grafana |
| P99 API 延迟 | < 2s | Prometheus + Grafana |
| 吞吐量 | ≥ 1000 QPS (API 网关) | k6 负载测试 |
| 并发会话数 | ≥ 500 (单集群) | 压力测试 |
| 沙箱启动时间 (L3) | < 500ms | 沙箱基准测试 |
| 沙箱启动时间 (L1) | < 5s | 沙箱基准测试 |
| 工作流节点流转 | < 200ms (不含人工节点) | 自动化测试 |
| 系统可用性 | 99.9% (生产) / 99.99% (目标) | Nightingale SLI |
| 数据库查询 (简单) | < 10ms | OceanBase 慢查询日志 |

### 7.2 性能测试场景

| 场景 | 负载模型 | 并发 | 持续时间 | 预期 |
|------|---------|------|---------|------|
| 基准测试 | 单用户，无思考时间 | 1 VU | 5min | 建立基线 |
| 负载测试 | 阶梯递增 10→50→100→200 | 200 VU | 15min | API 延迟不超 SLA |
| 稳定性测试 | 模拟真实用户行为 | 100 VU | 4h | 无内存泄漏，延迟稳定 |
| 峰值测试 | 突发流量 10× 基线 | 500 VU | 5min | 限流降级不崩溃 |
| 浸泡测试 | 持续中等负载 | 50 VU | 24h | 检测内存泄漏和慢查询 |

### 7.3 k6 脚本示例

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '2m', target: 10 },    // 热身
        { duration: '5m', target: 100 },   // 负载爬坡
        { duration: '10m', target: 100 },  // 保持负载
        { duration: '2m', target: 500 },   // 峰值
        { duration: '2m', target: 0 },     // 下降
    ],
    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<2000'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const headers = {
        'Authorization': `Bearer ${__ENV.TOKEN}`,
        'Content-Type': 'application/json',
    };
    
    // 会话消息发送（核心场景）
    const payload = JSON.stringify({
        content: '测试消息',
        messageType: 'text',
    });
    
    const res = http.post(
        `/api/v1/sessions/${__ENV.SESSION_ID}/messages`,
        payload,
        { headers }
    );
    
    check(res, {
        'status is 200': (r) => r.status === 200,
        'response time < 500ms': (r) => r.timings.duration < 500,
    });
    
    sleep(1);
}
```

---

## 8. 测试自动化与 CI/CD

### 8.1 流水线集成

```
代码提交 → SonarQube 分析 → 单元测试 → 集成测试 → 镜像构建 → 安全扫描 → E2E 测试 → 部署
   │            │             │          │          │          │          │          │
   │         代码质量       JUnit     TC 容器     Docker     Trivy     Cypress    Helm
   │         门禁           + JaCoCo   + MQ       Build     + ZAP      + Newman   Upgrade
   │
   └── 提交前 commit hook: ArchUnit + PMD + SpotBugs
```

### 8.2 质量门禁 (Quality Gate)

| 阶段 | 门禁条件 |
|------|---------|
| **Commit Hook** | PMD 无 ERROR, SpotBugs 无 HIGH, ArchUnit 通过 |
| **单元测试** | 行覆盖率 ≥ 80%, 核心领域层 ≥ 90%, 失败测试 = 0 |
| **集成测试** | 全部通过, 无 NPE / 超时 |
| **SAST** | SonarQube 无 Blocker/Critical 问题, 圈复杂度 ≤ 10 |
| **依赖扫描** | CVSS ≥ 7.0 阻断 |
| **容器扫描** | Trivy CRITICAL/HIGH = 0 |
| **E2E 测试** | P0 场景全部通过 |
| **性能测试** | P95 < 500ms, P99 < 2000ms |
| **上线审批** | 以上门禁全部通过 + Code Review 通过 |

### 8.3 测试报告

| 报告类型 | 输出格式 | 存放位置 |
|---------|---------|---------|
| 单元测试报告 | HTML (JaCoCo) | `target/site/jacoco/index.html` |
| 集成测试报告 | HTML (Surefire) | `target/site/surefire-report.html` |
| API 测试报告 | HTML (Newman) | `test-reports/api-report.html` |
| 安全扫描报告 | JSON (Trivy) | `test-reports/trivy-report.json` |
| 性能测试报告 | JSON (k6) | `test-reports/k6-report.json` |
| 覆盖率汇总 | XML (Cobertura) | `target/site/cobertura/coverage.xml` |

---

## 9. 测试环境管理

### 9.1 环境矩阵

| 环境 | 用途 | 数据库 | 配置 | 数据 |
|------|------|--------|------|------|
| **本地 (Local)** | 开发者自测 | H2 内存 / Testcontainers | application-local.yml | 测试数据工厂 |
| **开发 (Dev)** | 每日构建自动部署 | OceanBase 开发实例 | application-dev.yml | 脱敏生产数据子集 |
| **测试 (Test)** | 集成测试/性能测试 | OceanBase 测试实例 | application-test.yml | 模拟生产数据 (10 万条) |
| **预发 (Staging)** | 上线前验收 | OceanBase 预发实例 | application-staging.yml | 脱敏全量数据 |
| **生产 (Production)** | 线上运行 | OceanBase 生产集群 | application-prod.yml | 真实数据 |

### 9.2 测试数据管理

| 数据策略 | 说明 |
|---------|------|
| **工厂方法** | Domain 测试使用 Builder/Factory 创建实体 |
| **CSV 导入** | 集成测试使用 CSV 文件初始化数据库 |
| **数据清理** | 每次集成测试后 `@Sql(statements = "TRUNCATE TABLE ...")` |
| **脱敏规则** | 用户手机号 `138****1234`，邮箱 `zh***@example.com` |

---

## 10. 质量门禁

### 10.1 代码质量指标

| 指标 | 目标 | 测量工具 |
|------|------|---------|
| 圈复杂度 | < 10 (每个方法) | SonarQube |
| 方法长度 | < 50 行 | SonarQube |
| 文件长度 | < 500 行 | SonarQube |
| 重复代码 | < 5% | SonarQube |
| 注释覆盖率 | > 30% (公共 API) | SonarQube |
| 测试覆盖率 | > 80% (全局) | JaCoCo |

### 10.2 架构规则验证 (ArchUnit)

```java
@Test
void domainLayerShouldNotDependOnInfrastructure() {
    classes()
        .that().resideInAPackage("..domain..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("..domain..", "java..")
        .check(importClasses);
}

@Test
void repositoryShouldBeInterfaceOnly() {
    classes()
        .that().haveSimpleNameEndingWith("Repository")
        .should().beInterfaces()
        .check(importClasses);
}

@Test
void serviceShouldNotExposeRepository() {
    noClasses()
        .that().resideInAPackage("..application..")
        .should().accessClassesThat()
        .resideInAPackage("..infrastructure..")
        .check(importClasses);
}
```

---

## 11. 与统一文档的交叉引用

| 文档 | 章节 | 说明 |
|------|------|------|
| 20-unified-prd.md | §8 非功能需求 | 性能/安全/可用性需求来源 |
| 21-unified-architecture.md | §6 四层架构详解 | 各层测试策略对应 |
| 22-unified-db-schema.md | 全部 | 数据库测试基础 |
| 23-unified-api-spec.md | 全部 | API 集成测试基础 |
| 24-unified-security.md | §14.2 安全测试清单 | 安全测试基础 |
| 27-agent-sandbox-design.md | §5 安全加固 + §7 监控告警 | 沙箱测试基础 |
| 28-workflow-engine-design.md | §4 状态机设计 | 工作流状态机测试基础 |
| 30-workflow-api-design.md | 全部 | 工作流 API 测试基础 |

---

**文档结束 — 全文共 11 章、覆盖 4 个核心模块、80+ 测试用例、6 种安全测试类型、10 项质量门禁**
