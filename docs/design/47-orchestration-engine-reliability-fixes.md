# 编排引擎可靠性修复 · 立项方案

> 编号：47-orchestration-engine-reliability-fixes
> 版本：**V2** · 2026-09-24（V1 审核反馈四点全部调整：失败语义双模、超时参数表、重试策略细化、补充立项范围）
> 性质：修复实施方案（待审核确认后执行）
> 背景：编排图实机冒烟（docs/design/46 报告 M0-M3 交付后）暴露的引擎可靠性问题

---

## 一、背景与实证

两轮实机冒烟（测试图"冒烟-条件路由"，PIPELINE 5 节点，zhipu/glm-4.7-flash 与火山 coding 端点）观测到：

| # | 现象 | 影响 |
|---|------|------|
| P1 | AGENT 节点失败（HTTP 429/400）后仍发 `node_complete`，继续执行后继，整图 **SUCCEEDED** | 失败被静默吞掉；下游基于残缺输入继续产出 |
| P2 | 流式 LLM 调用间歇挂起 **4-7 分钟** | 节点长时间无进展；boundedElastic 线程被占 |
| P3 | 免费模型连续调用频繁 **429**（另有 400 风控 1214） | 一次 429 直接判节点失败，无退避重试 |
| P4 | `orchestration_node_execution` 表**未见节点级记录落库** | 节点级执行历史/回放缺数据支撑（用户要求纳入立项） |

---

## 二、问题一：AGENT 失败传播语义（P1）——双模式可配

### 2.1 根因

- `PipelineModeHandler.executeAgentNode`（`:245-262`）透传事件时不识别 `AgentEvent.ERROR`（`AgentEvent.java:100`）；`ReactAgentExecutor:454-468` 把异常转为 error **事件**（Flux 正常 complete）。
- complete 回调 `nodeCompleted`（`:469-481`）无 `walk.terminal` 守卫 → 失败节点照发 `node_complete`、写变量、推进后继 → 全图 SUCCEEDED。
- 对比：TOOL/PLAN/HUMAN/ROUTER 失败均显式 `failGraph`（`:273/293/347/587`）。仅 AGENT 缺。

### 2.2 V2 设计：双语义，图级可配（应用户调整意见）

新增图级配置 `config.continueOnFailure`（GraphNode/图 config 均可，图级优先）：

**模式 A：fail-fast（默认，`false`）**
- next 回调识别 error 事件 → `failGraph(walk, "<nodeId> 执行失败: " + errorMessage)`；
- 与 TOOL/PLAN/HUMAN/ROUTER 语义对齐；失败即整图 FAILED，前端已支持红色终态，零前端改动。

**模式 B：best-effort（`true`，失败继续）**
- 发 **error 事件**（带 nodeId，前端已有：节点标红）**不发 node_complete**、不写产出变量；
- 向该节点的 MERGE 下游"到账"失败占位（变量写入空串 + failedNodes 登记），使 MERGE"全部入边到齐"语义不悬挂；
- 无 MERGE 下游时直接继续其余分支；整图终态：存在失败节点 → `graph_complete(status=FAILED, reason="部分节点失败: ...")`（best-effort 跑完但整体如实标 FAILED，**不再出现"有失败仍 SUCCEEDED"**）。
- **零新增事件类型、零前端改动**。

**统一约束（两模式）**：失败节点不再发 `node_complete`、不写产出变量；`nodeCompleted` 与 `visit` 分派入口补 `walk.terminal` 守卫；SWARM/SUPERVISOR/DEBATE 的 AGENT 失败分支同批对齐（fail-fast 语义，改动极小）。

### 2.3 测试

a) fail-fast：error → `graph_complete(FAILED)`、无后继 node_start、无虚假 node_complete；
b) continue：error → 后继照常推进、失败节点无 node_complete、整图终态 FAILED；
c) late complete 竞态：failGraph 后 nodeCompleted 不生效；
d) 三 Handler 正常路径回归。

---

## 三、问题二：LLM 调用超时体系（P2）——参数全表可配

### 3.1 根因（三缺口）

| 缺口 | 位置 | 说明 |
|---|---|---|
| ① 流式无请求级总超时 | `OpenAiCompatibleClient.java:137-138` | 注释明确"流式不设 per-request timeout" |
| ② 空闲看门狗默认 180s 硬编码且未接配置 | `:44`、`setStreamIdleTimeoutMs:56` 装配处未调用 | 冒烟挂 4 分钟 ≈ 180s 空闲+恢复窗口 |
| ③ 响应头等待无兜底 | `:149` 同步 `send(...)` | 上游不回响应头时读挂起无界 |

### 3.2 V2 参数表（全部接入 `agent.engine.llm.*` yml 配置）

| 参数 | 默认 | 说明 |
|---|---|---|
| `stream-idle-timeout-ms` | **60_000** | 空闲看门狗：流内 60s 无任何字节即关流报错（真推理流式下 token 持续到达，不会误伤；挂起多为网络异常）。原 180s 偏大 |
| `header-timeout-ms` | **120_000** | 响应头阶段限时：`sendAsync(...).orTimeout(...)`，沿用现有 requestTimeout 语义（120s） |
| `stream-total-timeout-ms` | **0（不限）** | 单次流式总时长上限，0=关闭（推理 legitimately 可长，空闲阈值已防挂死；需要强管控的调用方可配，如 600_000） |
| `retry.max-attempts` / `retry.backoff-ms` | 2 / 1000 | 见问题三 |

实现：`AgentEngineProperties.Llm` 增加字段 → `AgentEngineAutoConfiguration` 创建 client 后统一 `setXxx` 接线。

### 3.3 测试

a) 可注入小空闲阈值 + 假慢流 → 看门狗关流并 `sink.error`；
b) mock 响应头永不返回 → 120s（测试注入更短）后超时错误；
c) 同步 `chat()` 行为回归不变。

---

## 四、问题三：限流（429）重试（P3）——策略细化

### 4.1 V2 策略表

| 项 | 设计 |
|---|---|
| 重试白名单 | HTTP **429**、502、503、504、连接类 IOException（重置/超时） |
| 不重试 | 400（参数/风控 1214）、401/403（认证）——非瞬态，重试放大问题 |
| 安全边界 | **仅未收到首 token 前**重试（`AtomicBoolean sawToken` 跨 attempt 守卫）；已吐 token 的流失败直接 error，防重复产出 |
| 次数 | 默认 **3 次尝试**（初次+2 重试；flash 免费模型 429 常见，2 次偏紧） |
| 退避 | 1s 起指数 + 抖动；响应头带 `Retry-After` 时取 `max(backoff, retryAfter)` |
| 生效位置 | `OpenAiCompatibleClient` 内（chatStream 与 chat 同构处理），对全部调用方透明 |
| 配置 | `retry.max-attempts`（0=关闭）、`retry.backoff-ms` |
| 可观测 | 每次重试 WARN：provider/model/attempt/等待 ms/原因 |

### 4.2 测试

429→200 序列重试成功且事件仅一份；连续 429 超限最终 error；sawToken 后不重试；400 立即 error；Retry-After 生效；maxAttempts=0 时行为与现状一致。

---

## 五、问题四（新增）：节点执行记录落库缺失（P4）

### 5.1 现象

冒烟中 n1 完成后 `orchestration_node_execution` 表无记录；节点级历史/回放（FR-14）缺数据。

### 5.2 设计

先排查（预计落库点在 `OrchestrationService` 的 SSE 桥接处缺失——现仅更新 `execEntity.currentNodeId`），补齐方案：事件桥接处对 `node_start/node_complete/error(FAILED)` **幂等落库**（executionId+nodeId 唯一，upsert status/durationMs），设计器执行回放（FR-14）直接受益。排查结论与实现随本轮交付（预估 0.5 人日）。

---

## 六、实施计划

| 顺序 | 内容 | 预估 |
|---|---|---|
| 1 | 问题一（双语义 + 三 Handler 对齐 + 守卫）+ 单测 | 0.5 人日 |
| 2 | 问题二（参数接线 + 响应头限时）+ 单测 | 0.5 人日 |
| 3 | 问题三（受限重试）+ 单测 | 0.5 人日 |
| 4 | 问题四（排查 + 节点落库）+ 单测 | 0.5 人日 |
| 5 | 全量编译 + 编排测试 + 实机复验（429 自动重试、失败即红、continue 模式跑通） | 0.5 人日 |

**合计约 2.5 人日**，四项相互独立可分 PR（fix(orchestration)/feat(llm-retry)/fix(orchestration-storage)）。

## 七、风险与回滚

- 问题一为**行为变更**：存量"失败继续"图默认将 FAILED 终止（语义修正目的）；依赖旧行为的图显式配 `config.continueOnFailure=true` 即可完全保持旧行为——**旧行为可通过配置 100% 保留**，回滚风险低。
- 问题二默认空闲 180s→60s：极端慢场景可 yml 调回。
- 问题三默认 3 次尝试：请求量放大约 3 倍上限，可配 0 关闭。
- 问题四仅增量落库，无行为变更。

## 八、验收标准

1. fail-fast：error → `graph_complete(FAILED)`，无后继执行、无虚假 node_complete；
2. continue 模式：失败节点标红且链路继续，整图终态 FAILED（不再有"有失败仍 SUCCEEDED"）；
3. 空闲/响应头超时按配置生效（可注入短阈值验证）；
4. 429 注入自动重试恢复；400 不重试；
5. 节点执行记录落库（upsert 幂等）；
6. 编译 + 编排测试全绿 + 实机复验通过。
