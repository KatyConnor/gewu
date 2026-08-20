# 10 · SPI 接口规范

> 格物 Agent 引擎的全部业务适配能力以 SPI 接口暴露，使用方按需实现，框架提供 NoOp 默认实现，**零业务实现即可运行**。本篇是全部 SPI 的权威清单：每个接口给出完整签名、方法契约、实现要求、NoOp 默认行为与业务适配备注。

## 10.1 SPI 总览

按"持久化 / 权限与审计 / 会话与沙箱 / 配置源 / 模型 / 编排扩展 / 记忆认知 / 协同"分组，共 11 个逻辑 SPI（含 14 个接口，见下表）：

| # | SPI 分组 | 接口 | 包 | NoOp 默认 | 详见章节 |
|---|----------|------|----|-----------|----------|
| 1 | 持久化 | `PersistenceService` | `spi` | `NoOpPersistenceService` | 10.2 |
| 2 | 权限 | `PermissionService` | `spi` | `NoOpPermissionService` | 10.3 |
| 3 | 审计 | `AuditService` | `spi` | `NoOpAuditService` | 10.4 |
| 4 | 会话上下文 | `SessionContextService` | `spi` | `NoOpSessionContextService` | 10.5 |
| 5 | 沙箱执行 | `SandboxExecutor` | `spi` | `NoOpSandboxExecutor` | 10.6 |
| 6 | 密钥解密 | `ApiKeyDecryptor` | `spi` | `NoOpApiKeyDecryptor` | 10.7 |
| 7 | 工具配置源 | `ToolConfigSource` | `tool` | `NoOpToolConfigSource` | 10.8 |
| 8 | LLM 供应商 | `LlmProvider` | `llm` | `NoOpLlmProvider` | 10.9 |
| 9 | MCP 服务器配置源 | `McpServerConfigSource` | `mcp` | `NoOpMcpServerConfigSource` | 10.10 |
| 10 | 角色配置源 | `RoleConfigSource` | `orchestration.role` | `NoOpRoleConfigSource` | 10.11 |
| 11 | 记忆 | `MemoryStore` / `MemoryRouter` | `memory` | `NoOpMemoryStore` / `NoOpMemoryRouter` | 10.12 |
| 11 | 认知 | `ReasoningKernel` / `EvolutionHook` / `ReflectionEngine` | `cognition` | 3 个 NoOp | 10.13 |
| 11 | 协同 | `HitlGateway` | `hitl` | `NoOpHitlGateway` | 10.14 |

约定：除特别说明，所有 SPI 实现注册为 Spring Bean 即覆盖默认；框架用 `@ConditionalOnMissingBean` 装配默认实现。

## 10.2 PersistenceService

```java
package com.gewu.agent.engine.spi;
import java.util.List;

public interface PersistenceService {
    AgentSpec loadAgent(String agentId);
    List<ToolConfig> loadAgentTools(String agentId);
    ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input);
    void completeExecution(String executionId, String output, Integer tokensUsed);
    void failExecution(String executionId, String errorMessage);
    ExecutionRecord getExecution(String executionId);
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `loadAgent` 不存在返回 `null`；`createExecution` 返回带 id 的 `ExecutionRecord`（status=running）；`completeExecution`/`failExecution` 更新状态与 `completedAt`/`durationMs` |
| **实现要求** | 桥接业务侧 Agent 配置表、Agent-工具绑定表、执行记录表；支持 MyBatis / JPA / 任意存储 |
| **NoOp 默认** | `NoOpPersistenceService`：内存 `ConcurrentHashMap` 存储；`loadAgent` 命中内存或返回 `null`；`loadAgentTools` 返回 `List.of()`；`createExecution` 生成 UUID 记录入内存；`get`/`complete`/`fail` 操作内存 Map。仅用于开箱即用与测试 |
| **业务适配备注** | 格物平台对应 `agent` / `agent_tool` / `execution_record` 三张表 |

相关模型（`spi` 包）：`AgentSpec`（id/name/description/modelProvider/modelName/modelConfig/systemPrompt/status）、`ToolConfig`（toolName/description/requestSchema/toolType:http|mcp|code_execute/endpoint/mcpServerId/timeoutMs/sortOrder/status）、`ExecutionRecord`（id/agentId/sessionId/userId/status:running|completed|failed/input/output/errorMessage/tokensUsed/startedAt/completedAt/durationMs）。

## 10.3 PermissionService

```java
public interface PermissionService {
    PermissionResult evaluate(String agentId, String toolName, String resource);
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 基于 RBAC/规则/策略评估 Agent 是否有权调用某 `toolName`（`resource` 可空）；返回 `PermissionResult` |
| **实现要求** | 返回 `allow()`/`deny(reason)`/`ask(reason)`；`ask` 表示需人工审批（`requireApproval=true`，触发 HITL） |
| **NoOp 默认** | `NoOpPermissionService`：固定返回 `PermissionResult.allow()` |
| **业务适配备注** | 格物平台基于角色 + 工具敏感级别 + 租户隔离策略评估 |

`PermissionResult`（`spi` 包）含 `effect`(allow/deny/ask)、`reason`、`requireApproval`，静态工厂 `allow()`/`deny(reason)`/`ask(reason)`。

## 10.4 AuditService

```java
public interface AuditService {
    void recordToolExecution(String userId, String agentId, String toolName, boolean success, long durationMs);
    default void recordAgentExecution(String userId, String agentId, String sessionId, boolean success, long durationMs) { }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `recordToolExecution` 记录一次工具调用审计；`recordAgentExecution` 默认空，记录 Agent 整体执行 |
| **实现要求** | 写入 `audit_log` 表或外部审计系统（ELK / 阿里云日志服务等） |
| **NoOp 默认** | `NoOpAuditService`：仅日志输出，不入库 |
| **业务适配备注** | 用于合规追溯与异常回放 |

## 10.5 SessionContextService

```java
public interface SessionContextService {
    List<Message> buildContextMessages(String sessionId, int limit);
    default void appendInteraction(String sessionId, String userId, String userMessage, String assistantContent) { }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `buildContextMessages` 返回历史消息列表（不含当前用户消息，供 LLM 上下文）；`appendInteraction` 持久化一轮对话（用户消息+助手回复），默认空 |
| **实现要求** | 对接会话历史表（按 sessionId/tenantId 隔离），`limit` 控制最大条数 |
| **NoOp 默认** | `NoOpSessionContextService`：`buildContextMessages` 返回 `List.of()` |
| **业务适配备注** | 格物平台按 sessionId + 创建时间倒序查询，注入 LLM 请求 messages |

`Message`（`llm.model` 包）：`role`(system/user/assistant/tool)/`content`/`toolCallId`/`name`。

## 10.6 SandboxExecutor

```java
public interface SandboxExecutor {
    ExecResult executeCode(String language, String code, int timeoutSeconds);
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 在隔离环境中执行 `language`（python/shell/node/...）的 `code`，超时 `timeoutSeconds`，返回 `ExecResult`（stdout/stderr/exitCode/success） |
| **实现要求** | 对接 Docker / Firecracker / gVisor 等沙箱后端；必须隔离文件系统、网络、CPU；超时强杀进程 |
| **NoOp 默认** | `NoOpSandboxExecutor`：**抛出** `AgentEngineException.of("SANDBOX_NOT_CONFIGURED", ...)`，**不通过** —— 使用 `code_execute` 工具必须自行实现 |
| **业务适配备注** | 格物平台基于 Firecracker 微 VM，镜像名由 `AgentSpec.modelConfig` 的 `sandboxImage` 指定 |

`ExecResult`（`spi` 包）：`stdout`/`stderr`/`exitCode`/`success`。

## 10.7 ApiKeyDecryptor

```java
public interface ApiKeyDecryptor {
    String decrypt(String cipherText);
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 解密 API Key 密文，未加密则原样返回 |
| **实现要求** | 接入国密 SM4 / AES / KMS；实现 `LlmProvider` 时注入此接口完成解密 |
| **NoOp 默认** | `NoOpApiKeyDecryptor`：原样透传 |
| **业务适配备注** | 格物平台数据库存国密 SM4 密文，运行时解密为明文交给 `LlmProviderConfig.apiKey` |

## 10.8 ToolConfigSource

```java
package com.gewu.agent.engine.tool;
import com.gewu.agent.engine.spi.ToolConfig;
import java.util.List;

public interface ToolConfigSource {
    List<ToolConfig> loadTools();
    default List<ToolConfig> loadToolsByAgent(String agentId) {
        return loadTools().stream()
                .filter(t -> agentId == null || agentId.equals(t.getToolName())).toList();
    }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `loadTools` 返回全部工具配置；`loadToolsByAgent` 默认从 `loadTools` 过滤（使用方可重写为更高效的按 Agent 查询） |
| **实现要求** | 从 DB/配置中心加载 `http`/`mcp`/`code_execute` 类型工具配置；仅 DB 配置工具走此通道，代码工具由 `@ToolProvider` 注册 |
| **NoOp 默认** | `NoOpToolConfigSource`：`loadTools` 返回 `List.of()`，此时仅代码注册工具可用 |
| **业务适配备注** | 格物平台对应 `tool` 表，按 `agent_id` 关联 |

## 10.9 LlmProvider

```java
package com.gewu.agent.engine.llm;
import java.util.List;

public interface LlmProvider {
    LlmProviderConfig getProviderConfig(String providerCode);
    default List<String> listProviderCodes() { return List.of(); }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 返回指定供应商配置，未配置返回 `null`；`listProviderCodes` 用于预热/校验 |
| **实现要求** | 从 `model_provider` 表或配置中心加载；返回的 `apiKey` 应为**已解密明文**（解密借助 `ApiKeyDecryptor`）；`baseUrl` 为 Chat Completions endpoint |
| **NoOp 默认** | `NoOpLlmProvider`：`getProviderConfig` 返回 `null`，此时仅代码注册的 `LlmClient` Bean 可用 |
| **业务适配备注** | 格物平台对应 `model_provider` 表，`api_key` 列存国密密文 |

`LlmProviderConfig` 是 **record**：`record LlmProviderConfig(String providerCode, String apiKey, String baseUrl)`。`LlmClientRegistry` 查找顺序：静态 `LlmClient` Bean → 动态缓存 → 经 `LlmProvider` 动态创建 `OpenAiCompatibleClient`，未命中抛 `AgentEngineException(PROVIDER_NOT_FOUND)`。

## 10.10 McpServerConfigSource

```java
package com.gewu.agent.engine.mcp;
import java.util.List;

public interface McpServerConfigSource {
    McpServerDescriptor loadServer(String serverId);
    default List<McpServerDescriptor> loadAllServers() { return List.of(); }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `loadServer` 不存在返回 `null`；`loadAllServers` 默认空 |
| **实现要求** | 从 DB 加载 `McpServerDescriptor`（id/name/transport:stdio|sse|streamable_http/command/args/env/url/status）；敏感字段在实现内解密 |
| **NoOp 默认** | `NoOpMcpServerConfigSource`：全返回空/null，MCP 工具不可用 |
| **业务适配备注** | 格物平台对应 `mcp_server` 表，详见 [06 MCP 集成](06-mcp.md) |

## 10.11 RoleConfigSource

```java
package com.gewu.agent.engine.orchestration.role;
import java.util.List;

public interface RoleConfigSource {
    List<AgentRoleSpec> loadRoles();
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | 返回外部角色配置列表，与 Spring 注入的 `AgentRoleSpec` Bean 合并入 `RoleRegistry` |
| **实现要求** | 从 DB 加载 DB 驱动的角色配置；返回的角色与内置 12 角色同名时**覆盖**默认（构造内 `putIfAbsent` 逻辑保证外部优先） |
| **NoOp 默认** | `NoOpRoleConfigSource`：返回空，仅用内置 12 个 SDLC 角色 |
| **业务适配备注** | 格物平台对应 `agent_role` 表，详见 [07 编排引擎](07-orchestration.md#710-sdlc-角色体系12-角色) |

## 10.12 MemoryStore 与 MemoryRouter

```java
package com.gewu.agent.engine.memory;
import java.util.List;

public interface MemoryStore {
    void store(MemoryFragment fragment);
    List<MemoryFragment> retrieve(String domain, String query, int topK);
    default List<MemoryFragment> retrieveByMetadata(String domain, java.util.Map<String, Object> filter) { return java.util.List.of(); }
    default void clear(String domain) { }
}

public interface MemoryRouter {
    List<Message> inject(String domain, List<Message> messages, String taskInput);
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `store` 写入片段（`vector` 由使用方生成）；`retrieve` 语义检索 topK；`retrieveByMetadata` 按 metadata 过滤；`clear` 删除域。`MemoryRouter.inject` 判断需求并注入记忆进 messages |
| **实现要求** | `MemoryStore` 对接 pgvector/Milvus/Weaviate 等向量库，`MemoryRouter` 实现规则优先+LLM 兜底的路由策略与 token 预算控制 |
| **NoOp 默认** | `NoOpMemoryStore`：空操作/空列表；`NoOpMemoryRouter`：原样返回 messages |
| **业务适配备注** | 四类记忆 semantic/episodic/procedural/parametric + experience 详见 [09 记忆与认知](09-memory-cognition.md) |

`MemoryFragment`：id/domain/type/content/vector/metadata/score。

## 10.13 ReasoningKernel / EvolutionHook / ReflectionEngine

```java
package com.gewu.agent.engine.cognition;
import java.util.List;

public interface ReasoningKernel {
    ReasoningResult plan(String task, String context);
    String routeSolver(String task);
    ReasoningResult critique(String output, List<String> acceptances);
}

public interface EvolutionHook {
    void onNodeComplete(String nodeId, String nodeResult);
    default String onGraphComplete(String graphId, String result, String reflection) { return null; }
    default void onGoalFailure(String goalId, String errorMessage) { }
    default void onGoalSuccess(String goalId, String result) { }
}

public interface ReflectionEngine {
    String reflect(String executionId, String result, String goal);
    default String replan(String goal, String reflection) { return goal; }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `plan`→子任务列表(PLAN)；`routeSolver`→策略字符串(TOOL_EXECUTION/CODE_GENERATION/KNOWLEDGE_RETRIEVAL/DIRECT_ANSWER)；`critique`→评估(CRITIC, score/accepted)。`EvolutionHook` 四钩子对应节点/图/目标完成事件。`reflect`→反思结论；`replan`→改进目标描述 |
| **实现要求** | 对接认知引擎（如格物 Wenshi 推理层）；`EvolutionHook` 调用 `ReflectionEngine.reflect` 并存储 `experience` 记忆 |
| **NoOp 默认** | `NoOpReasoningKernel`：plan 返回单子任务，routeSolver=DIRECT_ANSWER，critique accepted=true/score=1.0；`NoOpEvolutionHook`：空/null；`NoOpReflectionEngine`：返回 `"NoOp: 无反思"` |
| **业务适配备注** | 三者是 `AutonomousExecutor` 验收、`ReflexionRuntime` 重做、`GoalPlanner` 分解的认知后端，详见 [09 记忆与认知](09-memory-cognition.md) |

`ReasoningResult`：type(PLAN|SOLVE|CRITIC)/subtasks/answer/verdict/score/accepted/reasoning。

## 10.14 HitlGateway

```java
package com.gewu.agent.engine.hitl;
import reactor.core.publisher.Mono;

public interface HitlGateway {
    Mono<HumanDecision> requestApproval(ApprovalRequest request);
    void submitDecision(String approvalId, HumanDecision decision);
    default void takeover(String executionId, String operatorId) { }
    default void rollback(String executionId, String toNodeId) { }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `requestApproval` 返回 `Mono<HumanDecision>`，`submitDecision` 完成该 Mono 恢复执行；`takeover`/`rollback` 默认空 |
| **实现要求** | 维护 `approvalId → MonoSink` 映射；推送通知到审批队列/IM/Webhook；实现超时回退 |
| **NoOp 默认** | `NoOpHitlGateway`：立即返回 `APPROVED`(operatorId=system)，不阻塞、不通知 |
| **业务适配备注** | 对接审批中心 + IM 通知，详见 [08 HITL](08-hitl.md) |

`ApprovalRequest`：approvalId/executionId/nodeId/type:APPROVE_REJECT|INPUT|SELECT|EDIT/summary/artifact/options/timeoutSeconds。`HumanDecision`：decision:APPROVED|REJECTED|INPUT_VALUE|SELECTED|EDITED/value/operatorId。

## 10.15 统一异常与错误码

SPI 实现中可预期错误应抛 `AgentEngineException`（`com.gewu.agent.engine`，`code` + `message`）：

```java
public class AgentEngineException extends RuntimeException {
    private final String code;
    public String getCode() { return code; }
    public static AgentEngineException of(String code, String message) { ... }
}
```

错误码速查表：

| 错误码 | 含义 |
|--------|------|
| `PARAM_INVALID` | 参数非法 |
| `PROVIDER_NOT_FOUND` | LLM 供应商不存在 |
| `MODEL_NOT_RESOLVED` | 模型未能解析 |
| `TOOL_NOT_FOUND` | 工具未注册 |
| `TOOL_ROUNDS_EXCEEDED` | 工具调用轮次超限 |
| `SCHEMA_INVALID` | JSON Schema 校验失败 |
| `SSRF_BLOCKED` | SSRF 校验拦截 |
| `SANDBOX_DANGEROUS_OPERATION` | 沙箱危险操作拦截 |
| `SANDBOX_NOT_CONFIGURED` | 沙箱未配置 |
| `TOOL_CONFIG_INVALID` | 工具配置非法 |
| `TOOL_TIMEOUT` | 工具执行超时 |
| `TOOL_EXECUTION_FAILED` | 工具执行失败 |

## 10.16 小结

11 个 SPI 分组覆盖 Agent 系统的全部业务适配面：持久化、权限、审计、会话、沙箱、密钥、工具配置、LLM 供应商、MCP 服务器、角色、记忆认知、协同。每个 SPI 都有 NoOp 默认实现，使用方按需实现注册为 Bean 即覆盖默认。接口签名稳定、契约清晰、不绑定任何业务框架，使格物 Agent 引擎既能独立引入快速运行，又能深度对接业务系统。`AgentEngineAutoConfiguration` 是这些 Bean 的统一装配入口（`META-INF/spring/AutoConfiguration.imports`）。