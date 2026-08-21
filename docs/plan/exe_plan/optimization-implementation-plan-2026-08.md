# 格物平台优化方案与编码实施计划

> **版本**：V1.0 | **日期**：2026-08-20
> **上游输入**：`docs/architecture/PROJECT-FULL-ANALYSIS-REPORT-2026-08.md`（问题编号 D-1~D-20 / R1~R11 均引用该报告）
> **执行角色**：角色04 全栈开发（编码）+ 角色06 测试（验收）+ 角色09 SRE（部署），角色10 PM 跟踪
> **代码基线**：commit `5f0f841` + 未提交工作区（Sprint 1 首项即处理）
> **所有估算按 1 名资深工程师 + AI 辅助编码的节奏给出，预留 10% 调整空间**

---

## 目录

1. [总体原则与里程碑](#一总体原则与里程碑)
2. [Sprint 1：P0 止血（2 周）](#二sprint-1p0-止血2-周)
3. [Sprint 2：引擎测试补齐（2 周）](#三sprint-2引擎测试补齐2-周)
4. [Sprint 3：编排补齐与功能增强（4 周）](#四sprint-3编排补齐与功能增强4-周)
5. [Sprint 4：收敛与可观测闭环（4 周）](#五sprint-4收敛与可观测闭环4-周)
6. [Sprint 5：规模化与部署成熟度（4 周）](#六sprint-5规模化与部署成熟度4-周)
7. [依赖关系与关键路径](#七依赖关系与关键路径)
8. [质量门禁与验收度量（DoD）](#八质量门禁与验收度量dod)
9. [风险与回滚策略](#九风险与回滚策略)

---

## 一、总体原则与里程碑

### 1.1 排期原则

1. **正确性优先于能力**：先修协议缺陷/并发缺陷（P0），再补测试防护网，最后加能力；
2. **每个行为变更可独立回滚**：Bug 修复不改 API 契约；行为策略变化用配置开关；
3. **测试与代码同 Sprint 交付**：Sprint 1 的每个修复项自带单测，Sprint 2 集中补齐存量覆盖；
4. **决策门（Decision Gate）**：中期两项「认知层去留」「RocketMQ 去留」「desktop 去留」以实验/使用数据决策，避免沉没成本驱动开发。

### 1.2 里程碑总览

```
2026-08-24 ─ 09-04   Sprint 1  P0 止血          ← 唯一硬性截止点
2026-09-07 ─ 09-18   Sprint 2  引擎测试补齐
2026-09-21 ─ 10-16   Sprint 3  编排补齐与功能增强
2026-10-19 ─ 11-13   Sprint 4  收敛与可观测闭环
2026-11-16 ─ 12-11   Sprint 5  规模化与部署成熟度
```

| Sprint | 目标 | 关键交付 | 工作量 |
|--------|------|---------|--------|
| S1 | 消除全部 P0 正确性风险 | LLM 协议修复、并发/幂等修复、4 个确定性 bug、参数配置化、变更入库 | ~10 人日 |
| S2 | 核心引擎测试覆盖 ≥70% | 20+ 测试类、JaCoCo 门禁进 CI | ~9 人日 |
| S3 | 编排能力兑现 + 会话增值 | ROUTER/PARALLEL/MERGE 节点、暂停恢复取消、MCP Streamable HTTP、标题生成/重发/分享/归档 | ~18 人日 |
| S4 | 消灭双轨 + 可观测闭环 | 执行账本自动落库、成本回填、OTel 默认开、死代码清理、前端清理、A/B 实验框架 | ~16 人日 |
| S5 | 生产多副本就绪 | SSE Redis 广播、Helm 化、CI 部署真实化、jmeter 落地、认知 A/B 结论评审 | ~16 人日 |

---

## 二、Sprint 1：P0 止血（2 周）

### T1.1 悬空变更分主题入库 【R4】｜0.5 人日

| 项 | 内容 |
|----|------|
| 操作 | ① `git status` 复核 105 个文件；② 按主题分 4 批提交：**(a)** gewu-web Vite->Next 迁移残留清理（删除 vite.config.ts/tsconfig.node.json 等）、**(b)** agent-engine 认知架构（已 staged 的 A/MM 文件，Sprint 1 修复完成后一并提交）、**(c)** monitoring compose 调整、**(d)** 根 pom/start 脚本 |
| 规范 | Conventional Commits：`fix:` / `feat:` / `chore:`；每批提交前 `mvn -pl gewu-agent-engine -am compile` 确认可编译 |
| 验收 | `git status` 干净；CI build-and-test 通过 |
| 注意 | (b) 批次放在本 Sprint 末尾提交，使其包含 T1.2~T1.5 的修复 |

### T1.2 LLM 多轮工具调用协议修复 【D-2 / R2 + 新发现缺陷】｜2 人日

这是全计划**最高优先级**任务：当前多轮工具对话请求体缺少 assistant `tool_calls` 声明，严格供应商返回 400。

**改动 1：`llm/model/Message.java` 增加 toolCalls 字段**

```java
/** assistant 角色消息携带的工具调用请求（多轮工具对话协议必需） */
private List<ToolCall> toolCalls;
```

**改动 2：`llm/LlmRequestBodyBuilder.buildMessagesArray`（第 41-58 行）序列化 toolCalls**

```java
if (msg.getToolCallId() != null) {
    msgNode.put("tool_call_id", msg.getToolCallId());
}
if (msg.getName() != null) {
    msgNode.put("name", msg.getName());
}
// 新增：assistant 消息的 tool_calls 数组（OpenAI 规范：[{id, type:"function", function:{name, arguments}}]）
if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
    ArrayNode calls = msgNode.putArray("tool_calls");
    for (ToolCall tc : msg.getToolCalls()) {
        ObjectNode call = calls.addObject();
        call.put("id", tc.getId());
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", tc.getName());
        fn.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
    }
}
```

**改动 3：`core/ReactAgentExecutor.execute`（第 157-160 行）assistant 回灌携带 toolCalls**

```java
messages.add(Message.builder()
        .role("assistant")
        .content(response.getContent())
        .toolCalls(response.getToolCalls())   // 新增
        .build());
```

**改动 4：`core/ReactAgentExecutor.streamRound`（第 344-347 行）同步携带**

```java
messages.add(Message.builder()
        .role("assistant")
        .content(content)
        .toolCalls(toolCalls.isEmpty() ? null : toolCalls)   // 新增
        .build());
```

**改动 5（本次精读新发现）：流式路径 maxTokens 丢失**

`streamRound` 第 293 行 `resolveMaxTokens(null)` 恒用默认值，`AgentTask.maxTokens` 在流式被忽略。修复：

```java
// executeStream 中先解析并透传：
int maxTokens = resolveMaxTokens(task);
return Flux.defer(() -> streamRound(client, plan.model, messages, tools, toolConfigMap,
        toolContext, temperature, maxTokens, 0, plan.budget)) ...
// streamRound 增加 int maxTokens 参数，替换 resolveMaxTokens(null)
```

**改动 6：回归验证矩阵（人工 + 自动化）**

| 供应商 | 场景 | 验证点 |
|--------|------|--------|
| DeepSeek（chat/tool_call） | 2 轮工具调用对话 | 请求体含 assistant.tool_calls；无 400 |
| 智谱 GLM | 同上 + 流式 | 流式 toolCallDelta 累积正确 |
| OpenAI 兼容网关（最严格校验） | 同上 | 消息序列 role 交替合法 |

**单测（同 PR 交付）**：`LlmRequestBodyBuilderTest`--toolCalls 序列化格式、null 字段省略、tool_call_id/name 映射；`ReactAgentExecutorTest#streamTaskMaxTokensRespected`。

**验收**：三家供应商多轮工具对话 E2E 通过；抓包确认请求体符合 OpenAI 规范。

### T1.3 三个确定性 Bug 修复 【D-8 / D-9 / D-12】｜1 人日

**修复 1：`tool/ToolConfigSource.loadToolsByAgent`（语义错误：agentId 与 toolName 比较）**

`ToolConfig` 本身无 agent 绑定字段，默认实现无法按 agent 过滤。修正语义：

```java
default List<ToolConfig> loadToolsByAgent(String agentId) {
    if (agentId == null) {
        return loadTools();
    }
    // ToolConfig 无 agent 绑定信息，绑定关系由实现方（如 DbToolConfigSourceAdapter
    // 从 agent_tool 关联表查询）重写本方法实现；默认实现按"无绑定"处理。
    return List.of();
}
```

配套：检查 `gewu-application/.../agent/adapter/DbToolConfigSourceAdapter` 是否重写了 `loadToolsByAgent`；若其依赖默认实现则**补充重写**（按 agent_tool 关联表过滤），并在 `DbPersistenceServiceAdapter.loadAgentTools` 处回归验证（ReactAgentExecutor 第 514 行走 PersistenceService，两者数据源需一致）。

**修复 2：`tool/security/PromptInjectionDetector`（第 54/61 行裸 SecurityException）**

```java
// check()（SecurityCheck 插件路径）统一改抛框架异常：
throw AgentEngineException.of("PROMPT_INJECTION_DETECTED", "检测到提示注入攻击: " + p.pattern());
```

同时在类 Javadoc 明确两条路径的策略差异是有意设计：主输入链路中风险告警放行（用户体验），工具参数链路中风险也拦截（工具边界从严）。`ToolExecutor` 捕获处同步确认异常处理兼容。

**修复 3：`orchestration/mode/DebateModeHandler.resolveJudge`（第 156-158 行双 metadata 覆盖）**

```java
// 修正为单次合并（原第二次 .metadata() 覆盖丢失 status/verdict）：
sink.next(AgentEvent.builder().type("graph_complete")
        .metadata(Map.of("status", "SUCCESS",
                         "verdict", verdict.toString(),
                         "output", verdict.toString()))
        .build());
```

**验收**：3 个修复各有单测（loadToolsByAgent 语义、AgentEngineException 类型断言、graph_complete metadata 三键齐全）。

### T1.4 消息 seq 原子化 + clientId 幂等 【D-3 / R3】｜3 人日

**现状**：`MessageService.sendMessage`（第 54 行）`messageCount+1` 读改写；`SessionContextService.appendChatInteraction`（第 187 行）`selectMaxSeq()+1` 仍非原子；`client_id` 幂等字段未落地，`/chat/stream` 重放重复落库。

**改动 1：新增共享追加组件 `application/session/SessionMessageAppender`**

```java
@Component
@RequiredArgsConstructor
public class SessionMessageAppender {
    private static final int MAX_RETRY = 3;
    private final SessionMessageMapper messageMapper;
    private final SessionMapper sessionMapper;

    /** 带 seq 唯一键冲突重试的消息插入（uk_session_message_seq 兜底并发） */
    @Transactional
    public SessionMessage appendWithRetry(SessionMessage msg) {
        int attempt = 0;
        while (true) {
            Integer maxSeq = messageMapper.selectMaxSeq(msg.getSessionId());
            msg.setSeq((maxSeq == null ? 0 : maxSeq) + 1);
            try {
                messageMapper.insert(msg);
                return msg;
            } catch (DuplicateKeyException e) {
                if (++attempt >= MAX_RETRY) {
                    throw new BusinessException("消息发送过于频繁，请稍后重试");
                }
            }
        }
    }

    /** 计数器改为原子自增（替代读改写） */
    public void bumpSessionCounters(String sessionId) {
        sessionMapper.incrementMessageCount(sessionId);  // Mapper 新增
    }
}
```

`SessionMessageMapper` 新增：

```java
@Update("UPDATE session SET message_count = COALESCE(message_count,0) + 1, " +
        "last_message_at = NOW() WHERE id = #{sessionId} AND deleted = 0")
int incrementMessageCount(String sessionId);
```

**改动 2：两处调用方迁移**

- `MessageService.sendMessage`：seq 生成与计数器更新改走 Appender；
- `SessionContextService.appendChatInteraction`：两条消息插入改走 `appendWithRetry`，计数器改 `bumpSessionCounters`。

**改动 3：clientId 幂等（数据库迁移 V33）**

```sql
-- V33__message_client_id_idempotency.sql
ALTER TABLE session_message
    ADD UNIQUE KEY uk_session_message_client (session_id, client_id);
-- client_id 允许 NULL（历史数据/非幂等场景不受影响，MySQL 唯一索引忽略多 NULL）
```

- `SendMessageCommand` / `ChatRequest` 增加 `clientId` 字段（前端 chat.ts 生成 UUID）；
- `MessageService.sendMessage` 与 `appendChatInteraction`：clientId 非空时先查 `SELECT id FROM session_message WHERE session_id=? AND client_id=?`，命中直接返回已有消息（幂等返回，不报错）；
- 前端 `gewu-web/src/lib/chat.ts`：每次发送生成 `crypto.randomUUID()` 放入请求体；网络重试时复用同一 clientId。

**验收**：
- 单测：并发 20 线程同会话 append，断言无异常、seq 连续无重复；
- 幂等单测：同 clientId 二次调用返回相同消息 ID、表内仅 1 条；
- `/chat/stream` 重放（同 clientId）验证不重复落库、不重复扣 LLM token（缓存/查重在 LLM 调用前发生）。

### T1.5 硬编码参数配置化 【D-10 / R9】｜1.5 人日

**`config/AgentEngineProperties` 扩展**（保持现有嵌套结构）：

```java
public static class Llm {
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration requestTimeout = Duration.ofSeconds(120);   // 本次起真正生效
}
public static class Budget {
    private long timeBudgetMs = 300_000;        // 原 BudgetController 硬编码
    private int l1TokenDivisor = 5;
    private long l1TimeBudgetMs = 30_000;
    private int l1MaxRounds = 3;
    private int l3TokenMultiplier = 3;
    private int l3TimeMultiplier = 4;
    private int l3RoundsMultiplier = 2;
}
public static class Engine {
    private int defaultHistoryLimit = 50;       // 原 AgentEngineConfig 硬编码
}
public static class Lifecycle {
    private long heartbeatIntervalMs = 60_000;  // AgentLifecycleManager 硬编码
    private long globalTimeoutMs = 300_000;
}
```

**接线改动**：
1. `AgentEngineAutoConfiguration`：把 properties 传入 `OpenAiCompatibleClient` 构造（删除其内部第 63 行硬编码 `Duration.ofSeconds(120)`）、传入 `BudgetController`/`AgentEngineConfig`/`AgentLifecycleManager` 构造；
2. `BudgetController.createBudget` 的 L1/L3 分支改用注入的倍率字段；
3. 同步顺手修复 **D-11**：`OpenAiCompatibleClient.parseResponse` 第 181 行「content 为空回退取 reasoning_content 当答案」--改为 content 为空且**无 toolCalls** 时才回退，且在 LlmResponse 标记 `reasoningFallback=true`（新增字段），日志告警。

**验收**：`application.yml` 修改 `agent.engine.llm.requestTimeout: 60s` 后客户端超时行为变化（集成测试验证）；Budget L1 参数可调且单测覆盖新默认值。

### T1.6 异常路径消息保全 【R3 附带】｜0.5 人日

`AiChatController.chatStream` 完成回调中 `errorRef != null` 时不持久化--已消耗 token 的交互丢失。改动：错误时若 `accumulated` 已有实质内容（>0），仍调用 `appendChatInteraction`，assistantContent 后缀追加 `\n[异常中断: {errorMessage}]`，sessionId/clientId 幂等兜底防重。

**Sprint 1 总验收**：全部 P0 关闭；`mvn test` 全绿；三家供应商多轮工具对话回归通过；并发/幂等单测通过；`git status` 干净。

---

## 三、Sprint 2：引擎测试补齐（2 周）

### T2.1 测试基础设施 【1.5 人日】

- gewu-agent-engine 补 `src/test/java` 目录与测试依赖（spring-boot-starter-test 已在 pom）；
- 新增测试工具：`FixedLlmClient`（脚本化 LlmClient：按调用次数返回预设 Response/Flux<Chunk>，记录收到的 LlmRequest 供断言）、`NoOp 全家桶` 直接复用框架 defaults、`RecordingMetricService`/`RecordingTraceService`（捕获指标/Span 断言）；
- `pom.xml` 加 JaCoCo：`prepare-agent` + `report`，CI 上传。

### T2.2 核心测试清单（按优先级）

**P0 级（必须）**

| 测试类 | 用例（≥） | 覆盖目标 |
|--------|----------|---------|
| `ReactAgentExecutorTest` | 15 | 同步直答/工具轮回灌/预算熔断抛 BUDGET_EXCEEDED/轮次超限 TOOL_ROUNDS_EXCEEDED/缓存命中零调用/注入拦截/双工具并行均执行/流式事件序列 status->content->done/流式工具 tool_call->tool_executing->tool_result->递归下一轮/流式 maxTokens 透传（T1.2）/assistant.toolCalls 序列化进请求体/流式异常转 ERROR 事件不抛出/length 截断提示/BUDGET_EXCEEDED 事件 metadata/storeExperience 与 recordSuccess 调用 |
| `LlmRequestBodyBuilderTest` | 8 | 四种 role 序列化、toolCalls 格式、null 省略、tools 数组 schema 解析失败降级 |
| `OpenAiCompatibleClientTest` | 10 | 用 JDK `HttpServer` 假服务：同步响应解析、usage、finishReason、reasoning/content 分离、content 空+有 toolCalls 不回退（T1.5）、流式 data: 行解析、[DONE]、toolCallDelta 累积、HTTP 错误码异常、超时 |
| `BudgetControllerTest` | 8 | L1/L2/L3 配额、70/90/100 四态、consume 记账、elapsedMs |
| `ToolExecutorTest` | 8 | 五段管线顺序（安全->权限->执行->截断->审计）、permission deny、输出截断 10KB、HTTP 重定向逐跳 SSRF、代码工具优先 |

**P1 级（重要）**

| 测试类 | 用例（≥） | 覆盖目标 |
|--------|----------|---------|
| `SecurityChain` 系列 5 个测试类 | 20 | PromptInjection 高/中危模式表、PII 四类脱敏、SSRF 内网/回环/白名单/链路本地/redirect、SchemaValidator 递归、CodeScanner Python 18+Shell 16 模式抽查 |
| `Orchestration` 系列：`PipelineModeHandlerTest`/`HandoffParserTest`/`VersionedContextTest`/`AntiRunawayGuardTest`/`ConflictResolverTest`/`AgentLifecycleManagerTest` | 18 | 拓扑排序、HUMAN 节点挂起/驳回回滚、HANDOFF/FINISH 解析、防环、六重边界各触发一次、四策略冲突、心跳超时/DFS 死锁 |
| `ComplexityRouterTest`/`DualSystemRouterTest`/`ConfidenceGateTest` | 9 | L1-L3 规则表、System1/2 选择、四级门控决策 |
| `McpServerManagerTest`/`StdioMcpClientTest` | 5 | 连接缓存复用、JSON-RPC 组包、工具结果解析 |
| `SessionMessageAppenderTest`/`SessionContextServiceTest` 补充 | 6 | seq 冲突重试上限、幂等返回、压缩三分支（不压缩/压缩/truncate 回退） |

**度量**：JaCoCo 行覆盖--`core`/`budget`/`tool`/`tool.security`/`llm` 包 ≥ **75%**，`orchestration` ≥ **65%**，全模块汇总 ≥ **70%**。

### T2.3 CI 门禁 【0.5 人日】

`.github/workflows/ci-cd.yml` 的 build-and-test job 增加：
- `mvn verify`（含 JaCoCo check：`BUNDLE {LINE COVEREDRATIO: 0.70}`，作用域限定 gewu-agent-engine）；
- 测试报告 artifact 上传保留 30 天。

**Sprint 2 总验收**：新增测试类 22+、用例 110+；覆盖率达标并进 CI 强制门禁；发现并修复的缺陷形成清单（测试先行往往再挖出 3-5 个并发/边界 bug，属预期收益）。

---

## 四、Sprint 3：编排补齐与功能增强（4 周）

### T3.1 编排节点类型补齐 【D-4 / R5】｜5 人日

**现状**：`Orchestrator` 仅实现 AGENT/HUMAN；TOOL/ROUTER/PARALLEL/MERGE/SUBGRAPH 占位；pause/resume/cancel 空存根。

**改动 1：新增四类节点执行器（`Orchestrator` 分派扩展）**

| 节点类型 | 执行语义 | 实现要点 |
|---------|---------|---------|
| `TOOL` | 单工具节点：config 指定 toolName+arguments（模板变量 `${变量名}` 从 VersionedContext 渲染） | 委托 ToolExecutor，产出 putVariable(nodeId, output) |
| `ROUTER` | 条件路由：求值出边 `GraphEdge.condition`（表达式：`var:x == 'y'` / `contains` / `else`），首个命中出边继续 | 表达式求值器 60 行内自研（不支持任意 EL，防注入） |
| `PARALLEL` | 扇出：所有出边目标节点 flatMap 并行（复用工具线程池），全部完成后到达 MERGE | 计数器 + `Mono.when` |
| `MERGE` | 汇聚：等待 N 条入边产出（默认策略：concat 全部产出 / config.strategy=json_merge） | 缓冲区按入边计数 |

**改动 2：pause/resume/cancel 落地（基于 V26 orchestration_execution 表状态机）**

```java
// OrchestrationEngine 存根实现：
public Mono<Void> pause(String executionId) {
    // ① 状态置 PAUSED（乐观锁 UPDATE ... WHERE status='RUNNING'）
    // ② 向该执行的事件流发 PAUSE 信号：Orchestrator 每个节点循环开头检查
    //    executionRecord.getStatus()，非 RUNNING 则挂起（Sink 保活或落盘恢复点）
}
public Mono<Void> resume(String executionId) {
    // 状态置 RUNNING + 从"最后一个 node_complete 的节点"重建执行（VersionedContext
    // 已随节点完成持久化到 execution 快照字段），跳过已完成节点
}
public Mono<Void> cancel(String executionId) { // 状态 CANCELED + dispose 订阅 + 事件流发 graph_complete(CANCELED)
}
```

配套：`OrchestrationService` 现有 REST 端点已存在（`/orchestration/executions/{id}/pause|resume|cancel`），打通引擎实现即可；`VersionedContext` 增加快照序列化（`snapshot()` 已有，补 JSON 持久化与反序列化）。

**改动 3：编排层 13 个字面量事件提为 `AgentEvent` 常量**（GRAPH_START/NODE_START/...），同步前端 ProcessTracker 白名单。

**验收**：四类节点单测（含 PARALLEL 异常传播、MERGE 超时）；pause->resume 断点续跑集成测试（2 节点图，第 1 节点完成后暂停，恢复后从第 2 节点继续且变量保留）；前端 OrchestrationPage 暂停/恢复按钮真实可用。

### T3.2 MCP 客户端升级 【D-7】｜4 人日

1. **协议握手补全**：`initialize` 后发送 `notifications/initialized`（stdio 与 HTTP 两传输）；
2. **新增 `StreamableHttpClient`**（MCP 2025-03-26 规范）：
   - 单端点 `POST {url}` JSON-RPC，响应可为 `application/json` 或 `text/event-stream`（SSE 包裹 JSON-RPC 响应）；
   - `Mcp-Session-Id` 响应头回传后续请求；
   - GET SSE 长连接服务器推送（可选支持，超时容错）；
3. **`McpServerDescriptor.transport` 增加 `streamable_http` 类型**；`McpServerManager` 按类型分派三种客户端；
4. 旧 `SseMcpClient` 标记 @Deprecated（保留一版兼容），删除死字段 `endpointUrl`；
5. **复用 Spring ObjectMapper**（`McpServerManager`/客户端构造注入，消除私有 new，【D-14】顺手关闭）。

**验收**：对真实 MCP Server（如 docker 跑一个 mcp-server-filesystem / mcp-fetch）完成 listTools+callTool 集成测试；三种传输单测通过；前端 McpServerPage 连接状态真实反映。

### T3.3 会话增值功能 【D-18】｜6 人日

**3.3.1 标题自动生成（1 人日）**

```
触发：appendChatInteraction 后 session.messageCount == 2（首轮问答完成）
执行：@Async 方法调用 LlmClient（当前会话 provider）：
      prompt = "为以下对话生成不超过12字的标题，直接输出标题：\n用户:{前100字}\nAI:{前200字}"
      temperature=0.3, maxTokens=32
更新：session.title = 结果（仅当当前标题为"新对话"/空）
```

前端 ChatPage 会话列表无需改造（下次拉取自然更新，可选轮询一次）。

**3.3.2 消息重新生成（2 人日）**

```
POST /api/v1/ai/sessions/{sessionId}/messages/{messageId}/regenerate  (SSE)
语义：messageId 必须为 assistant 消息
  ① 找到其前一条 user 消息（同会话 seq < 目标 seq 的最近 user）
  ② 逻辑删除目标 assistant 消息及其后的所有消息（deleted=1，可恢复）
  ③ 以原 user 内容重走 chatStream（新的 clientId）
前端：消息气泡增加"重新生成"按钮，流式渲染复用现有 ProcessTracker
```

**3.3.3 归档与分享（2 人日）**

```
PUT /api/v1/sessions/{id}/archive   -> status=2, timeArchived=now（事务）
PUT /api/v1/sessions/{id}/unarchive -> status=0
POST /api/v1/sessions/{id}/share    -> 生成 slug（ULID 前 10 位小写），isPublic=1，返回 shareUrl
GET  /api/v1/share/{slug}           -> 免鉴权只读会话元数据+消息（脱敏：不含成员邮箱等）
DELETE /api/v1/sessions/{id}/share  -> isPublic=0, share_url=null
```

`uk_session_slug` 唯一索引已存在，直接使用；分享只读接口加入网关 skip-paths。

**3.3.4 置顶（1 人日）**：迁移 V34 `ALTER TABLE session ADD COLUMN pinned TINYINT DEFAULT 0, ADD INDEX idx_session_pinned(pinned)`；`PUT /{id}/pin`；listSessions 排序 `pinned DESC, last_message_at DESC`；前端列表加图钉。

**验收**：四功能 E2E（含幂等：重复 share 幂等返回同 slug；regenerate 后历史消息恢复正确）；前端联调通过。

### T3.4 A/B 实验框架（为 S4 认知决策铺路）｜3 人日

1. `agent` 表 metadata 增加 `experimentGroup` 字段（无迁移成本，metadata 为 JSON）；
2. `AgentExecutionEngine` 执行时把 group 写入 `agent_execution`（V26 表已有 metadata 列）；
3. `/api/v1/evaluations/experiment-compare?from&to`：按 group 聚合成功率/平均时长/LLM-as-Judge 分值/成本，输出对比报告（复用 SPC 与 LlmJudge 现有实现）；
4. 预置四组对照组配置样例（纯 ReAct / +复杂度路由 / +模型路由 / 全开）。

**验收**：用 20 条固定题集（docs/design/32-test-strategy 的用例池）跑通四组对比报表。

---

## 五、Sprint 4：收敛与可观测闭环（4 周）

### T4.1 执行账本与成本回填 【R7】｜3 人日

1. `AgentExecutionEngine.executeAgent/executeAgentStream` 包装：进入时 `agentExecutionService.createExecution`（含 experimentGroup），完成/失败时 complete/fail（复用现有服务，当前仅 REST 手动调用）；
2. `LlmResponse.usage`（prompt/completion/total + reasoning）回填 `agent_execution` 与 `session.tokens_input/tokens_output/tokens_reasoning`；`session.cost` 按 ModelProviderConfig 单价（model_provider 表加 `price_per_1k_input/output` 两列，迁移 V35）累计；
3. 流式路径 usage 缺失时按 `content.length()/4` 估算并标记 `estimated=1`；
4. `UsagePage`/`StatsController` 从真实数据聚合（当前部分 mock）。

**验收**：一次多轮工具对话后，agent_execution 与 session 的 token/cost 字段与供应商后台用量一致（误差 <5%）。

### T4.2 可观测接通 【R7】｜2 人日

1. 生产 `application.yml`/K8s configmap：`OTEL_TRACING_ENABLED=true`（Jaeger 已在 K8s 清单）；
2. 语义缓存命中率、模型路由切换率、预算熔断率三个业务指标补进 `agent-dashboard.json`；
3. 慢 LLM 调用（>30s）、预算熔断、HITL 触发三类日志接入告警规则（PrometheusRule 增 2 条）。

### T4.3 双轨收敛（决策门 + 执行）｜5 人日

| 收敛项 | 决策门（Sprint 4 第 1 周评审） | 执行 |
|--------|------------------------------|------|
| LLM 引擎 legacy vs wenshi | 依据 T3.4 A/B 四组数据 + 一个月生产 `routing` 配置使用情况 | 败者进 archive：删除路由分支与对应 Adapter（Wenshi* 6 适配器或 LegacyLlmClientAdapter），保留引擎 SPI 不动 |
| workflow vs orchestration | 明确边界写入 `docs/design/28` 修订版：**workflow=人工审批流（顺序节点）、orchestration=Agent DAG**；前端导航与文案区分 | 若边界成立则双轨保留但互相引用；不合并 |
| RocketMQ | 若 T4.1 后仍无真实消费者需求 | 移除 compose/pom/K8s configmap 中的 RocketMQ（保留 DomainEventPublisher 接口，落 Spring ApplicationEvent 本地事件实现）【D-17】 |
| 前端 API 封装双轨 | 无条件收敛 | session.ts 等 6 个自带 authFetch 的模块迁移到 `request.ts` 拦截器模式，删除重复代码 ~400 行【D-19 部分】 |

### T4.4 死代码清理 【D-16 / R11】｜2 人日

决策规则：**「三个月内无排期实现意图的空壳，删除」**（git 历史可找回）：

| 对象 | 处置 |
|------|------|
| `Part`/`SessionInput`/`SessionContextEpoch` 实体+Mapper | 若 OpenCode 式分段消息/上下文纪元未进 S5 规划 -> 删实体/Mapper + 迁移 V36 drop 三表 |
| `SseEventManager.sendToUser` | 修复语义（按 userId 维护连接索引）--S5 多副本改造会复用，保留并修复 |
| `CacheKeys.session()` | 删除 |
| `gewu-desktop/` 空目录 | 决策门：立项（出 PRD 排 S6+）或删除目录 |
| `domain/permission`、`domain/tool` 空包 | 删除 |
| 前端 store mock 会话/「张明远」硬编码/空 slices 目录/未接线搜索框 | 清理或接线【D-19】 |

### T4.5 事件协议完善 【D-13】｜2 人日

- 阶段二预留的 `experience_saved`/`failure_recorded`/`confidence_check`/`verification_result` 事件真正发射（storeExperience 后、recordFailure 后、DualLoopVerifier/ConfidenceGate 内）；
- 编排 13 常量已在 T3.1 提取；`AgentEvent` Javadoc 更新事件目录表（同步 `docs/agent-engine/13-event-protocol.md`）；
- 前端 ProcessTracker 消费新事件（experience_saved 显示"经验已沉淀"提示等）。

### T4.6 认知层接线或裁剪（决策门）｜4 人日

依据 T3.4 A/B 数据对三个半成品做**数据驱动决策**：

| 项 | 若实验显示收益（延迟/成本可接受且质量提升） | 若无收益 |
|----|------------------------------------------|---------|
| D-6 DualSystemRouter 不切换运行时 | 接线：System2 -> PLAN_EXECUTE 运行时 + modelTier 驱动 ModelSelector 偏好 | 保留日志级输出，docs 标注"仅观测" |
| D-5 ReflexionRuntime 简化 | 实现真实反思循环：Critic 评估失败 -> ReflectionEngine.reflect -> 注入反思记忆重跑（≤3 轮） | 降级标注 @Beta，docs 移出主线 |
| Wenshi 三层记忆 | 扩大实验题集与场景（代码生成/知识 QA 分组）验证 | 记忆 SPI 保留（引擎能力），Wenshi 实现进维护态 |

---

## 六、Sprint 5：规模化与部署成熟度（4 周）

### T5.1 SSE 多副本广播改造 【前置条件级】｜5 人日

**问题**：`SseEventManager` 连接表是进程内存，K8s 2 副本下 HITL 审批/协作消息会推到错误实例。

**方案**（DragonflyDB 已就绪，选 Redis Pub/Sub）：

```
发布：sendEvent(sessionId, event) ->
      ① 本地 emitters 直发（同实例命中）
      ② PUBLISH gewu:sse:{sessionId} {eventJson}（覆盖未命中实例）
订阅：每实例启动时 PSUBSCRIBE gewu:sse:*，收到后投递本地 emitters（幂等：
      消息带 originInstanceId，与本地相同则跳过，避免双发）
HITL：DbHitlGatewayAdapter 的 Sinks.One 等待表同样跨实例：决策提交走
      Redis Pub/Sub 回流（gewu:hitl:{approvalId}），各实例本地唤醒
兜底：断连重连由前端现有逻辑承担；Pub/Sub 不可用时降级为本地（单副本行为）
```

改动点：`SseEventManager`（双通道发送+订阅线程）、`DbHitlGatewayAdapter`（决策回流）、配置开关 `gewu.sse.distributed=true`（默认 false，单副本零依赖）。

**验收**：本地 docker-compose 起 2 个后端实例（不同端口），浏览器 A（连实例 1）发起编排含 HUMAN 节点，浏览器 B（连实例 2）收到 approval_required 并批准，实例 1 的流程恢复继续。

### T5.2 Helm 化与 K8s 补全 【R10】｜4 人日

1. `deploy/helm/gewu-platform/` Chart：主服务/gateway/sandbox/frontend 四个 deployment + configmap/secret 模板 + values-dev/staging/prod；
2. 修复 `docker-compose.prod.yml` 第 156 行 YAML 缩进瑕疵；前端/gateway/sandbox 补 Dockerfile 与 compose 服务（compose.prod 三件套）；
3. `.github/workflows/ci-cd.yml` deploy 阶段真实化：`kubectl -n gewu set image ...` + `rollout status`（对齐 .gitlab-ci.yml 已有写法），staging 自动 / production 手动。

### T5.3 性能测试落地 【R10】｜3 人日

按 `deploy/jmeter/PERFORMANCE-TEST-PLAN.md` 实现 `gewu-platform.jmx`（5 场景：登录 100 并发 / 会话列表 200 / **AI 流式 50 并发（重点：首字节<500ms、完整<10s）** / 工作流 30 / 混合 750）+ CI nightly 跑 50 并发 SSE 冒烟（超阈值 fail）；产出基线报告归档 `deploy/jmeter/baseline-2026-12.md`。

### T5.4 语义缓存与成本调优 ｜2 人日

- `SemanticResponseCacheAdapter` 命中阈值与 TTL 按场景配置（代码生成场景关闭，QA 场景 0.95/24h）；
- Session 成本看板（T4.1 数据源）：Top Agent/Top Session/日均成本，超预算告警规则。

### T5.5 阶段五路线评审 ｜2 人日

对 `docs/design/41` 阶段五剩余项（在线学习/红蓝对抗/知识图谱/联邦协作）做 go/no-go 评审，输出 V3.0 路线图修订；Sprint 1-5 全量复盘报告归档 `docs/project/`。

---

## 七、依赖关系与关键路径

```
T1.1 ─┬─► T1.2 ─► T1.3 ─► T1.4 ─► T1.5 ─► T1.6     （Sprint 1 串行，T1.2 最关键）
      └─► T2.1 ─► T2.2 ─► T2.3                       （Sprint 2 依赖 T1.2 的 Message 改动定型）
T2.2 ─► T3.1/T3.2/T3.3（三者可并行）
T3.4（A/B 框架）──► T4.6（认知决策依赖数据）
T3.1 ─► T4.5（事件常量先行提取）
T4.1 ─► T5.4（成本看板依赖 usage 回填）
T4.4 修复 sendToUser ─► T5.1（多副本复用用户索引）
关键路径：T1.2 → T2.2 → T3.1 → T4.3 → T5.1 → T5.5（约 16 周）
```

**并行建议**：若 2 人协作，一人主线（引擎/后端），一人支线（T3.3 会话功能 → T4.1 账本 → T5.2 Helm → T5.3 压测），整体可压缩至 ~11 周。

---

## 八、质量门禁与验收度量（DoD）

**每个任务级 DoD**：代码 + 单测 + （涉及行为的）docs 更新 + `mvn verify` 绿 + Conventional Commit；数据库变更必须走 Flyway 新版本号（当前 V32，本计划用到 V33~V36），**禁止修改已发布迁移**。

**Sprint 级度量**：

| 指标 | S1 | S2 | S3 | S4 | S5 |
|------|----|----|----|----|----|
| P0 缺陷存量 | 0 | 0 | 0 | 0 | 0 |
| 引擎核心包行覆盖 | - | ≥70%（core/llm ≥75%） | 保持 | 保持 | 保持 |
| 多轮工具对话回归（3 供应商） | ✅ | 自动化进 CI | 保持 | 保持 | 保持 |
| 并发 seq 冲突（20 线程压测） | 0 | 自动化 | - | - | - |
| 编排节点类型可用 | - | - | 8/8 类 | - | - |
| pause/resume 断点续跑 | - | - | 集成测试 ✅ | - | - |
| token/成本核算误差 | - | - | - | <5% | 看板上线 |
| 2 实例 SSE 广播 | - | - | - | - | ✅ |
| 50 并发 SSE 首字节 | - | - | - | - | <500ms |

---

## 九、风险与回滚策略

| 风险 | 概率 | 缓解 | 回滚 |
|------|------|------|------|
| T1.2 协议修复导致部分宽松供应商不兼容新 tool_calls 字段 | 低（OpenAI 规范内） | 三供应商回归矩阵；字段仅在非空时序列化 | revert 单 commit；字段为增量不破坏反序列化 |
| T1.4 V33 唯一索引在历史脏数据上创建失败 | 中 | 迁移前先跑重复 client_id 检测 SQL（历史全 NULL，预期无风险）；失败则跳过索引仅保留应用层查重 | Flyway repair + 去掉索引的 V33R 版本 |
| T3.1 pause/resume 状态机复杂度超预期 | 中 | 拆两期：先 cancel + pause（终止语义），resume 断点续跑二期；Orchestrator 节点循环状态检查点已预埋 | 功能开关 `/orchestration` 配置 `lifecycle.enabled=false` 退化为只读执行 |
| T5.1 Pub/Sub 改造引入消息丢失 | 中 | 本地直发优先 + origin 幂等；HITL 决策走 DB 状态兜底轮询（30s） | `gewu.sse.distributed=false` 一键回单实例行为 |
| A/B 实验样本量不足导致错误裁剪认知层 | 中 | 最小 3 轮实验、每组 ≥50 样本；裁剪保留 SPI 只动实现（可恢复） | 引擎 SPI 未删除，重实现即可 |
| 人员单点（单人节奏估算） | 高 | 每个 Sprint 产出独立可发布；文档先行（本计划 + 报告） | 按 Sprint 粒度暂停/续做无耦合 |

---

## 十、下一环节传递信息

- **给开发（角色04）**：从 T1.1 开始按序执行；每个任务本文已给到类/方法/行号级改动说明，可直接转 issue（建议按 T 编号建 GitHub Issue 并挂 Sprint 里程碑）。
- **给测试（角色06）**：T2.2 用例表即测试设计基线；S1 各修复的验收标准已内联，E2E 场景以「三家供应商多轮工具对话」「并发 20 线程 seq」为冒烟集。
- **给 SRE（角色09）**：T5.2/T5.3 前置依赖 T1.1 变更入库；monitoring 三个新告警规则在 T4.2 提前评审。
- **给 PM（角色10）**：三个决策门时间点--S4 第 1 周（双轨收敛）、S4 第 3 周（认知去留）、S5 第 3 周（阶段五路线）；风险表每周站会过一遍。

*计划完 | 与分析报告（PROJECT-FULL-ANALYSIS-REPORT-2026-08.md）配套使用 | 建议每 Sprint 结束回写实际耗时与偏差，滚动修正后续估算*
