# 格物致虚平台源码审查报告

## 概要

| 项目 | 内容 |
|------|------|
| 审查对象 | `home-wnn-devcode-ai-code-gewu-platform`（格物致虚 AI 平台） |
| 审查范围 | 后端 Java 源码（gewu-* 模块）+ 前端 TypeScript 源码（gewu-web）+ 配置与部署脚本 |
| 审查日期 | 2026-07-28 |
| 审查语言 | Java（383 文件）、TypeScript（57 文件） |
| 代码审查评分 | **5.5 / 10** |
| 安全审查评分 | **4.5 / 10** |
| 发现问题 | 代码质量 18 项，安全风险 17 项（含 5 项严重） |

## 一、代码审查问题列表

### 🔴 严重（立即修复）

| 编号 | 文件 | 行号 | 问题描述 | 修复建议 |
|------|------|------|----------|----------|
| CR-001 | `gewu-common/src/main/java/com/gewu/common/crypto/PasswordPolicy.java` | 1-102 | `PasswordPolicy` 已完整实现，但 `AuthService.register()` 未调用，导致密码策略完全失效 | 在注册和修改密码流程中注入并调用 `passwordPolicy.validate(password, username)` |
| CR-002 | `gewu-gateway/src/main/resources/application.yml` | 38 | JWT 密钥硬编码在配置文件中（`gewu-platform-jwt-secret-key-must-be-at-least-256-bits-long-for-hs256`） | 改为从环境变量注入，并在启动时校验长度 ≥ 256 bit；禁止提交真实密钥到版本库 |
| CR-003 | `gewu-interface/src/main/java/com/gewu/interfaceconfig/security/SecurityConfig.java` | 52-55 | CORS 配置为 `setAllowedOriginPatterns(List.of("*"))` 且 `setAllowCredentials(true)`，违反安全规范，允许任意跨域携带凭证 | 改为显式配置可信域名；生产环境禁止 `*` + `AllowCredentials` 组合 |
| CR-004 | `gewu-interface/src/main/java/com/gewu/interfaceconfig/security/SecurityConfig.java` | 44-50 | `/api/v1/ai/**`、`/api/v1/models/**`、`/actuator/**` 全部 `permitAll()`，AI 对话和模型配置接口无需认证即可访问 | 移除 `permitAll()`；AI 对话需认证；`/actuator` 仅允许内部 IP 或管理角色 |
| CR-005 | `gewu-application/src/main/java/com/gewu/application/ai/ModelConfigService.java` | 97-112 | LLM 供应商 API Key 以明文存储到数据库，且列表接口未做访问控制 | 使用 SM4 或 AES-GCM 加密存储；返回 DTO 时脱敏；管理接口增加管理员角色校验 |

### 🟠 高危（24 小时内）

| 编号 | 文件 | 行号 | 问题描述 | 修复建议 |
|------|------|------|----------|----------|
| CR-006 | `gewu-application/src/main/java/com/gewu/application/agent/ToolExecutionService.java` | 106-124 | `executeViaHttp` 对 `tool.getEndpoint()` 做 SSRF 校验，但 `allowedHostsConfig` 为空时白名单失效，仅依赖内网 IP 黑名单 | 默认启用白名单；对重定向做跟随限制；增加 URL 签名或预注册机制 |
| CR-007 | `gewu-application/src/main/java/com/gewu/application/agent/McpServerService.java` | 32-48 | `createServer()` 将用户传入的 `command`、`args`、`env` 直接写入数据库并在后续通过 `ProcessBuilder` 执行，存在命令注入风险 | 校验命令白名单；沙箱化 stdio 进程；限制可执行路径 |
| CR-008 | `gewu-sandbox/src/main/java/com/gewu/sandbox/controller/SandboxController.java` | 79-84 | `/api/v1/sandboxes/execute` 接口允许任意认证用户执行任意代码，无配额/权限/内容审查 | 增加管理员审批或沙箱执行权限；限制可执行语言；记录完整审计日志 |
| CR-009 | `gewu-sandbox/src/main/java/com/gewu/sandbox/provider/DockerSandboxProvider.java` | 127-159 | `exec()` 使用 `/bin/sh -c command` 执行用户命令，存在 shell 注入 | 使用命令数组而非 `sh -c`；对命令做白名单/黑名单校验 |
| CR-010 | `gewu-interface/src/main/java/com/gewu/interfaceapi/controller/ModelConfigController.java` | 32-122 | 模型配置 CRUD 接口未做任何角色/权限校验，普通用户可创建、修改、删除供应商和模型 | 增加 `@PreAuthorize("hasRole('ADMIN')")` 或等价权限检查 |

### 🟡 中危（1 周内）

| 编号 | 文件 | 行号 | 问题描述 | 修复建议 |
|------|------|------|----------|----------|
| CR-011 | `gewu-application/src/main/java/com/gewu/application/agent/AgentExecutionEngine.java` | 29-442 | 类长度 442 行，职责过多（prompt 构建、工具循环、流式处理、温度计算） | 拆分为 `PromptBuilder`、`ToolLoopExecutor`、`StreamChunkAssembler` 等独立组件 |
| CR-012 | `gewu-application/src/main/java/com/gewu/application/agent/AgentExecutionEngine.java` | 211-232 | 流式工具调用使用 `CompletableFuture.allOf(...).join()` 在响应流中阻塞等待工具执行 | 改为异步链式编排，避免阻塞 Netty/Reactor 线程 |
| CR-013 | `gewu-application/src/main/java/com/gewu/application/session/SessionContextService.java` | 50-98 | 上下文压缩策略简单粗暴，仅保留最近 6 条消息，可能丢失关键上下文 | 实现基于语义重要性的智能摘要；保留系统消息和用户明确标记的重要消息 |
| CR-014 | `gewu-application/src/main/java/com/gewu/application/agent/AgentService.java` | 109-117 | `checkOwnership()` 仅在更新/删除时检查，`createAgent()` 和 `getAgent()` 未限制 | 创建时绑定当前用户；查询时增加用户/组织隔离 |
| CR-015 | `gewu-application/src/main/java/com/gewu/application/session/SessionContextService.java` | 50-98 | `getContext()` 和 `buildContextMessages()` 未校验当前用户是否有权访问该会话 | 增加会话所有权校验；查询时加入 `createdBy` 或成员关系条件 |
| CR-016 | `gewu-infrastructure/src/main/java/com/gewu/infrastructure/llm/OpenAiCompatibleClient.java` | 81-155 | 流式响应中 `BufferedReader` 未关闭，可能导致连接泄漏 | 使用 try-with-resources 或确保在流结束时关闭 reader |
| CR-017 | `gewu-common/src/main/java/com/gewu/common/crypto/PasswordHasher.java` | 10-47 | 使用 SM3 做密码哈希。SM3 是快速哈希算法，即使加盐迭代 10000 次仍不适合口令保护 | 迁移到 `PBKDF2`、`bcrypt` 或 `Argon2id`；兼容旧口令需渐进升级 |
| CR-018 | `gewu-application/src/main/java/com/gewu/application/agent/ToolExecutionService.java` | 163-212 | `executeInSandbox()` 中 `code` 直接来自用户输入，未做危险代码扫描 | 增加代码静态扫描；禁用危险系统调用；限制网络/文件访问 |

### 🟢 建议（下个迭代）

| 编号 | 文件 | 行号 | 问题描述 | 修复建议 |
|------|------|------|----------|----------|
| CR-019 | `gewu-interface/src/main/java/com/gewu/interfaceconfig/security/XssRequestWrapper.java` | 92-103 | XSS 包装器对 `application/json` 请求体不做转义，而前后端主要使用 JSON 交互 | 对 JSON 字符串值做上下文感知转义，或改用输出端转义 |
| CR-020 | `gewu-web/src/lib/token.ts` | 15-19 | Access Token 和 Refresh Token 均存储在 `localStorage` 中，存在 XSS 被盗风险 | 将 Access Token 改为 `httpOnly` Cookie；Refresh Token 存储在服务端或安全 Cookie |
| CR-021 | 多处 Controller | - | 大量删除/更新接口未校验资源所有权，存在水平越权风险 | 统一实现 `@Service` 层所有权校验切面或注解 |
| CR-022 | `gewu-application/src/main/java/com/gewu/application/agent/AgentExecutionEngine.java` | 37 | `TOOL_EXECUTOR` 是固定大小线程池（8），未设置拒绝策略和队列边界 | 配置有界队列和拒绝策略；根据负载动态调整 |
| CR-023 | `gewu-infrastructure/src/main/java/com/gewu/infrastructure/llm/OpenAiCompatibleClient.java` | 92-94 | 日志打印 API Key 长度（`已配置(n字符)`），泄露密钥长度信息 | 统一打印为 `已配置` / `未配置`，不泄露长度 |
| CR-024 | 全局 | - | 测试覆盖率不足，核心安全路径（权限、SSRF、认证）缺少单元测试 | 为核心安全类补充测试；将安全测试纳入 CI |

## 二、安全审查报告

### OWASP Top 10 检查结论

| 编号 | 风险领域 | 状态 | 说明 |
|------|----------|------|------|
| A01 | 访问控制失效 | ❌ 存在 | AI/模型/actuator 接口开放；大量 CRUD 缺少所有权校验 |
| A02 | 加密失败 | ❌ 存在 | API Key 明文存储；JWT 密钥硬编码；密码哈希算法不当 |
| A03 | 注入 | ❌ 存在 | Shell/命令注入（沙箱、MCP stdio）；SSRF 风险 |
| A04 | 不安全设计 | ⚠️ 部分 | Agent 工具链缺乏零信任设计；权限模型过于简单 |
| A05 | 安全配置错误 | ❌ 存在 | CORS 过宽； actuator 开放；默认配置不安全 |
| A06 | 易受攻击和过时组件 | ⚠️ 待确认 | 未运行 SCA 扫描，需检查 log4j、bcprov、docker-java 等依赖 |
| A07 | 认证失效 | ⚠️ 部分 | JWT 实现基本正确，但密钥硬编码削弱安全性 |
| A08 | 数据完整性失效 | ⚠️ 部分 | LLM 输出直接渲染 Markdown，虽有转义但缺少输出消毒策略 |
| A09 | 日志监控不足 | ⚠️ 部分 | 有审计日志但缺少安全事件集中告警 |
| A10 | SSRF | ❌ 存在 | 工具 HTTP 调用可被用于 SSRF，白名单非强制 |

### 风险等级分布

| 等级 | 数量 | 平均修复时限 |
|------|------|--------------|
| 🔴 严重 | 5 | 立即修复 |
| 🟠 高危 | 5 | 24 小时内 |
| 🟡 中危 | 8 | 1 周内 |
| 🟢 低危 | 6 | 下个迭代 |

## 三、关键漏洞详细分析

### 3.1 认证与授权缺陷

**问题**：`SecurityConfig` 将 `/api/v1/ai/**` 和 `/api/v1/models/**` 设为 `permitAll()`，意味着未登录用户可直接调用 AI 对话和模型管理接口。同时 `ModelConfigController` 未使用 `@PreAuthorize`，任何登录用户都能增删改 LLM 供应商配置（包括 API Key）。

**影响**：
- 未授权用户可消耗 LLM 配额、获取模型列表
- 普通用户可读取/修改存储的 API Key
- 系统管理员功能暴露给所有用户

**修复建议**：
```java
// SecurityConfig
.requestMatchers("/api/v1/ai/**").authenticated()
.requestMatchers("/api/v1/models/**").hasRole("ADMIN")
.requestMatchers("/actuator/**").hasIpAddress("127.0.0.1")

// ModelConfigController
@PreAuthorize("hasRole('ADMIN')")
```

### 3.2 敏感数据泄露风险

**问题 1**：`ModelConfigService.createProvider()` 将 `apiKey` 明文写入 `model_provider` 表；`ProviderDTO` 虽未返回 `apiKey`，但数据库明文存储一旦泄露将直接暴露第三方 LLM 凭证。

**问题 2**：`OpenAiCompatibleClient.chatStream()` 日志打印 `apiKey.length()`，泄露密钥长度。

**修复建议**：
- 使用 SM4/AES-GCM 加密 `apiKey`，密钥通过 KMS/环境变量管理
- 返回 DTO 时隐藏/脱敏 API Key
- 日志中统一显示 `已配置/未配置`

### 3.3 SSRF 与命令注入

**问题 1**：`ToolExecutionService.validateUrl()` 仅在 `allowedHostsConfig` 非空时启用白名单，默认仅依赖内网 IP 黑名单，可通过 DNS 重绑定、IPv6、URL 编码等方式绕过。

**问题 2**：`StdioMcpClient` 直接执行数据库中存储的 `command` 和 `args`；`DockerSandboxProvider.exec()` 使用 `/bin/sh -c` 执行用户命令，存在命令注入。

**修复建议**：
- 默认启用严格白名单，禁止空配置绕过
- 禁止跟随 HTTP 重定向
- Stdio MCP 使用命令白名单 + 参数校验
- 沙箱命令使用数组形式，避免 shell 解释

### 3.4 沙箱安全风险

**问题**：`/api/v1/sandboxes/execute` 允许任意认证用户执行任意代码，缺少：
- 执行内容审查
- 每用户配额限制
- 危险系统调用限制
- 网络访问默认策略外的细粒度控制

**修复建议**：
- 增加执行审批流程或仅允许特定角色
- 默认禁用网络，显式启用需审批
- 使用 seccomp/seccomp-bpf 限制系统调用
- 增加 CPU/内存/磁盘配额和超时

### 3.5 密码安全策略未生效

**问题**：`PasswordPolicy` 已实现等保三级要求（8 位、3 种字符、禁止用户名和常见弱口令），但 `AuthService.register()` 完全未调用，用户可注册 `"123456"` 等弱密码。

**修复建议**：
```java
// AuthService.register
passwordPolicy.validate(command.getPassword(), command.getUsername());
```

### 3.6 CORS 配置风险

**问题**：`SecurityConfig.corsConfigurationSource()` 配置 `AllowedOriginPatterns("*")` + `AllowCredentials(true)`，允许任意网站携带用户 Cookie/Token 访问后端。

**修复建议**：
```java
config.setAllowedOriginPatterns(List.of(
    "https://gewu.example.com",
    "https://app.gewu.example.com"
));
// 或仅允许同源，由网关统一处理跨域
```

## 四、修复优先级

### 立即修复（P0）
1. **CR-001**：注册时启用密码策略校验
2. **CR-002**：JWT 密钥改为环境变量注入，禁止硬编码
3. **CR-003**：收紧 CORS 配置
4. **CR-004**：移除 AI/模型/actuator 的 `permitAll()`
5. **CR-005**：API Key 加密存储并脱敏返回

### 24 小时内（P1）
6. **CR-006**：SSRF 白名单默认强制启用
7. **CR-007**：MCP stdio 命令注入防护
8. **CR-008**：沙箱代码执行权限控制
9. **CR-009**：沙箱命令避免 `sh -c`
10. **CR-010**：模型配置接口增加管理员权限

### 1 周内（P2）
11. **CR-011**：拆分 `AgentExecutionEngine`
12. **CR-012**：流式工具调用异步化
13. **CR-013/015**：会话上下文访问控制与智能压缩
14. **CR-014**：Agent 创建/查询权限隔离
15. **CR-016**：关闭流式 reader 防止连接泄漏
16. **CR-017**：密码哈希迁移到 bcrypt/Argon2id
17. **CR-018**：沙箱代码危险操作扫描

### 下个迭代（P3）
18. **CR-019-024**：JSON XSS、Token 存储、越权统一校验、线程池配置、日志脱敏、安全测试覆盖

## 五、最佳实践建议

### 5.1 架构层面
- 引入 API Gateway 统一认证、限流、审计，后端服务之间使用 mTLS
- 建立 RBAC + ABAC 混合权限模型，区分系统管理员、Agent 开发者、普通用户
- LLM 供应商 API Key 集中存储于 KMS/Vault，应用按需申请临时凭证

### 5.2 流程层面
- 建立安全代码审查清单（SDL），每次提交必须覆盖 OWASP Top 10 关键项
- 引入 SAST（SonarQube/CodeQL）、SCA（Snyk/Trivy）、DAST（OWASP ZAP）到 CI/CD
- 定期进行渗透测试和红队演练

### 5.3 技术层面
- 统一使用 `@PreAuthorize` 或方法级注解进行权限控制
- 所有外部调用（HTTP、数据库、LLM、MCP）统一接入熔断/限流/超时
- 敏感操作（删除、修改配置、沙箱执行）必须记录不可篡改的审计日志
- 关键配置（密钥、密码、Token）全部通过环境变量或配置中心注入

## 六、总结

格物致虚平台在业务功能上已具备 AI Agent、工具调用、沙箱执行、MCP 接入等能力，但**在安全与代码质量上存在较多高风险问题**。最严重的问题集中在：

1. **认证授权缺失**：AI 和模型接口开放、管理员功能未保护
2. **密钥管理薄弱**：JWT 密钥硬编码、LLM API Key 明文存储
3. **注入与 SSRF**：沙箱/MCP 命令注入、工具 HTTP 调用 SSRF
4. **密码策略失效**：已实现但未调用
5. **CORS 配置过宽**：允许任意跨域携带凭证

建议立即启动安全加固专项，按照 P0 → P1 → P2 优先级逐项修复，并在修复后补充安全回归测试。
