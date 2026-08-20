# 13 · 流式事件协议

> 格物 Agent 引擎的核心基于 Reactor `Flux<AgentEvent>`，所有执行过程以增量事件输出。本篇是 `AgentEvent`（`com.gewu.agent.engine.core.event`）的完整协议定义：ReAct 层 8 种基础事件 + 编排层扩展事件的逐字段说明、JSON 示例、SSE 传输格式与前后端集成，供使用方与前端对接参考。

## 13.1 AgentEvent 数据模型

```java
package com.gewu.agent.engine.core.event;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentEvent {
    private String type;                    // 事件类型
    private String content;                 // 文本内容（status / content 事件）
    private String reasoning;               // 推理内容（thinking 事件）
    private ToolCallInfo toolCall;          // 工具调用（tool_call / tool_executing）
    private ToolResultInfo toolResult;      // 工具结果（tool_result）
    private String errorMessage;            // 错误信息（error 事件）
    private String nodeId;                  // 编排层扩展：节点 ID
    private String role;                    // 编排层扩展：角色编码
    private Map<String, Object> metadata;  // 编排层扩展：附加元数据

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ToolCallInfo {
        private String id;          // 工具调用 ID（对应 LLM function call id）
        private String name;        // 工具名
        private String arguments;   // 参数（JSON 字符串）
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ToolResultInfo {
        private String toolCallId;  // 关联的 toolCall.id
        private String name;        // 工具名
        private String result;      // 执行结果文本
    }

    // ===== 事件类型常量（ReAct 层） =====
    public static final String STATUS = "status";
    public static final String THINKING = "thinking";
    public static final String CONTENT = "content";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_EXECUTING = "tool_executing";
    public static final String TOOL_RESULT = "tool_result";
    public static final String DONE = "done";
    public static final String ERROR = "error";
}
```

字段使用约定：ReAct 层事件主要用 `content`/`reasoning`/`toolCall`/`toolResult`/`errorMessage`；编排层扩展事件主要用 `nodeId`/`role`/`metadata` 携带结构化数据，`type` 区分。

## 13.2 事件分类速览

| 层 | 事件类型 | 触发时机 | 主要字段 |
|----|----------|----------|----------|
| ReAct | `status` | 状态提示（"正在思考..."） | `content` |
| ReAct | `thinking` | 推理/思考增量 | `reasoning` |
| ReAct | `content` | 正式回复增量（最终展示用） | `content` |
| ReAct | `tool_call` | 决定调用工具 | `toolCall{id,name,arguments}` |
| ReAct | `tool_executing` | 工具开始执行 | `toolCall{id,name}` |
| ReAct | `tool_result` | 工具执行完成 | `toolResult{toolCallId,name,result}` |
| ReAct | `done` | 执行完成 | (`metadata`, 可空) |
| ReAct | `error` | 执行出错 | `errorMessage` |
| 编排 | `graph_start` | 编排图开始 | `metadata{executionId,mode}` |
| 编排 | `node_start` | 节点开始 | `nodeId`,`role`,可选`metadata` |
| 编排 | `node_complete` | 节点完成 | `nodeId`,`role`,可选`metadata` |
| 编排 | `handoff` | Swarm 交接 | `metadata{reason,depth}` |
| 编排 | `approval_required` | 请求人工审批 | `metadata{approvalId,type,summary}` |
| 编排 | `approval_result` | 审批决策结果 | `metadata{approvalId,decision}` |
| 编排 | `goal_decomposed` | 目标分解完成 | `metadata{graphId,mode}` |
| 编排 | `reflection` | 进入反思重规划 | `metadata{iteration,reason}` |
| 编排 | `goal_start` | 自主目标开始 | `metadata{goalId,description}` |
| 编排 | `goal_complete` | 自主目标完成 | `metadata{status,iterations,output/reason}` |
| 编排 | `graph_complete` | 编排图完成 | `metadata{status,output}` |

## 13.3 ReAct 层事件详解

### 13.3.1 status — 状态提示

引擎阶段性提示，非推理/回复内容，前端可作为轻量状态展示。

```json
{
  "type": "status",
  "content": "正在思考..."
}
```

### 13.3.2 thinking — 思考增量

模型推理过程内容（带 reasoning_content 的模型）。可折叠展示供用户观察链路。

```json
{
  "type": "thinking",
  "content": null,
  "reasoning": "用户要快速排序，我先回顾快排思路再写代码..."
}
```

### 13.3.3 content — 回复增量

最终展示给用户的回复正文增量，前端按顺序拼接即为完整答案。

```json
{
  "type": "content",
  "content": "以下是 Java 实现的快速排序：\n\n```java\n"
}
```

### 13.3.4 tool_call — 工具调用决策

LLM 决定调用某工具，含工具调用 ID（用于关联结果）、工具名、参数 JSON。

```json
{
  "type": "tool_call",
  "toolCall": {
    "id": "call_abc123",
    "name": "git_commit",
    "arguments": "{\"message\":\"fix: 修复登录 Bug\"}"
  }
}
```

### 13.3.5 tool_executing — 工具执行中

工具即将开始执行（已通过安全管线），前端可显示"执行中"加载态。

```json
{
  "type": "tool_executing",
  "toolCall": {
    "id": "call_abc123",
    "name": "git_commit",
    "arguments": "{\"message\":\"fix: 修复登录 Bug\"}"
  }
}
```

### 13.3.6 tool_result — 工具执行结果

工具执行完成，`toolResult.toolCallId` 关联 13.3.4 的 `toolCall.id`。`result` 为工具输出文本（经 `maxOutputSize` 截断标记）。

```json
{
  "type": "tool_result",
  "toolResult": {
    "toolCallId": "call_abc123",
    "name": "git_commit",
    "result": "committed: a1b2c3d"
  }
}
```

### 13.3.7 done — 执行完成

ReAct 循环正常结束信号，前端据此关闭加载态。

```json
{
  "type": "done",
  "metadata": {}
}
```

### 13.3.8 error — 执行错误

执行异常，`errorMessage` 含错误详情，附带 `AgentEngineException` 的错误码语义（见 [10 SPI 规范](10-spi-reference.md#1015-统一异常与错误码)）。

```json
{
  "type": "error",
  "errorMessage": "工具执行超时: git_commit (TOOL_TIMEOUT)"
}
```

## 13.4 编排层扩展事件详解

### 13.4.1 graph_start — 编排图开始

```json
{
  "type": "graph_start",
  "metadata": { "executionId": "exec-001", "mode": "PIPELINE" }
}
```

### 13.4.2 node_start — 节点开始

```json
{
  "type": "node_start",
  "nodeId": "n2",
  "role": "ARCHITECT",
  "metadata": { "phase": "DEBATE" }
}
```

### 13.4.3 node_complete — 节点完成

```json
{
  "type": "node_complete",
  "nodeId": "n2",
  "role": "ARCHITECT",
  "metadata": { "outputLen": 1024, "phase": "DEBATE" }
}
```

### 13.4.4 handoff — Swarm 交接

```json
{
  "type": "handoff",
  "metadata": { "reason": "FINISH_OR_LIMIT", "depth": 4 }
}
```

`reason` 取值如 `FINISH_OR_LIMIT`（到 maxHandoffs 或节点已访问）。

### 13.4.5 approval_required — 请求人工审批

```json
{
  "type": "approval_required",
  "metadata": {
    "approvalId": "appr-001",
    "type": "APPROVE_REJECT",
    "summary": "架构设计评审"
  }
}
```

### 13.4.6 approval_result — 审批决策结果

```json
{
  "type": "approval_result",
  "metadata": {
    "approvalId": "appr-001",
    "decision": "APPROVED",
    "operatorId": "user-42"
  }
}
```

### 13.4.7 goal_decomposed — 目标分解完成

```json
{
  "type": "goal_decomposed",
  "metadata": { "graphId": "g-7f3a", "mode": "PIPELINE" }
}
```

### 13.4.8 reflection — 进入反思重规划

验收未通过时触发，提示进入下一轮迭代。

```json
{
  "type": "reflection",
  "metadata": { "iteration": 1, "reason": "验收未通过，进入反思重规划" }
}
```

### 13.4.9 goal_start — 自主目标开始

```json
{
  "type": "goal_start",
  "metadata": { "goalId": "g-001", "description": "实现 OAuth2 登录模块" }
}
```

### 13.4.10 goal_complete — 自主目标完成

成功时 `status=SUCCESS` 带 `output` 与 `iterations`；失败时 `status=FAILED` 带 `reason` 与 `iterations`。

```json
{
  "type": "goal_complete",
  "metadata": { "status": "SUCCESS", "iterations": 2, "output": "OAuth2 模块已实现并通过审计" }
}
```

```json
{
  "type": "goal_complete",
  "metadata": { "status": "FAILED", "reason": "超出自主迭代上限", "iterations": 5 }
}
```

### 13.4.11 graph_complete — 编排图完成

```json
{
  "type": "graph_complete",
  "metadata": { "status": "SUCCESS", "output": "已产出架构设计文档" }
}
```

## 13.5 SSE 传输格式

`Flux<AgentEvent>` 通过 SSE 推送，每条事件序列化为单行 JSON，以 `data: ` 前缀 + 双换行 `\n\n` 结束：

```
data: {"type":"status","content":"正在思考..."}

data: {"type":"thinking","reasoning":"用户要快排，先回顾思路..."}

data: {"type":"content","content":"以下是 Java 快速排序：\n\n```java\n"}

data: {"type":"content","content":"public class QuickSort { ...\n"}

data: {"type":"done","metadata":{}}

```

约定：

- 每条 `data:` 行为单个 `AgentEvent` 的 JSON（紧凑序列化）。
- 事件之间以空行（`\n\n`）分隔。
- 不使用 SSE 的 `event:` / `id:` 字段，统一用 `type` 区分。
- `error` 事件后流终止；`done`/`goal_complete`/`graph_complete` 为正常终止信号。

## 13.6 Spring MVC StreamingResponseBody 集成示例

```java
@RestController
@RequestMapping("/api/agent")
public class AgentSseController {

    private final AgentEngine agentEngine;
    private final ObjectMapper mapper;        // Jackson

    public AgentSseController(AgentEngine agentEngine, ObjectMapper mapper) {
        this.agentEngine = agentEngine; this.mapper = mapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody ChatRequest req) throws Exception {
        Flux<AgentEvent> flux = agentEngine.executeStream(AgentTask.builder()
                .modelProvider(req.provider()).modelName(req.model())
                .message(req.message()).userId(req.userId()).build());

        StreamingResponseBody body = out -> {
            flux.map(this::toSseLine)
                .doOnNext(line -> writeRaw(out, line))
                .doOnError(e -> writeRaw(out, toSseError(e)))
                .doFinally(sig -> { try { out.flush(); } catch (Exception ignored) { } })
                .blockLast();
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    private String toSseLine(AgentEvent event) {
        try {
            return "data: " + mapper.writeValueAsString(event) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"error\",\"errorMessage\":\"序列化失败\"}\n\n";
        }
    }

    private String toSseError(Throwable e) {
        return "data: {\"type\":\"error\",\"errorMessage\":\"" + escape(e.getMessage()) + "\"}\n\n";
    }

    private void writeRaw(OutputStream out, String line) {
        try { out.write(line.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        catch (IOException ignored) { }
    }

    private String escape(String s) { return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n"); }

    public record ChatRequest(String provider, String model, String userId, String message) { }
}
```

要点：`produces = TEXT_EVENT_STREAM_VALUE`；`StreamingResponseBody` 持有 `OutputStream` 同步写行；`blockLast()` 在响应线程内驱动 `Flux`；错误统一转 `error` SSE 行。亦可直接用 `Flux<String>` 返回（Spring 原生 SSE 支持）或 `SseEmitter`。

## 13.7 前端处理示例（JS / EventSource）

浏览器消费 SSE（POST 场景需用 `fetch` + `ReadableStream`，或通过 EventSourcePolyfill）：

```javascript
async function streamChat(message, provider = "deepseek", model = "deepseek-chat") {
  const resp = await fetch("/api/agent/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
    body: JSON.stringify({ provider, model, userId: "u1", message })
  });

  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let answer = "";
  let reasoning = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split("\n\n");
    buffer = frames.pop();                       // 保留不完整片段
    for (const frame of frames) {
      if (!frame.startsWith("data: ")) continue;
      const evt = JSON.parse(frame.slice(6));
      handleEvent(evt);
    }
  }

  function handleEvent(e) {
    switch (e.type) {
      case "status":    showStatus(e.content); break;
      case "thinking":  reasoning += e.reasoning || ""; showThinking(reasoning); break;
      case "content":   answer += e.content || ""; renderAnswer(answer); break;
      case "tool_call": showToolCall(e.toolCall.name, e.toolCall.arguments); break;
      case "tool_result": showToolResult(e.toolResult.name, e.toolResult.result); break;
      case "error":     showError(e.errorMessage); break;
      case "done":      finish(); break;
      case "graph_start": case "node_start": case "node_complete":
      case "goal_start":  case "goal_complete": case "graph_complete":
        showOrchestrationStep(e); break;
      default: break;
    }
  }
}
```

前端按 `type` 分派：`content` 增量拼正文、`thinking` 增量拼推理、`tool_call`/`tool_result` 展示工具过程、`done` 关闭加载态、`error` 提示。编排类事件用于展示多 Agent 协作进度。

## 13.8 与编排引擎的协作

`Orchestrator` 与各 `ModeHandler` 在转发底层 ReAct 事件时，会把 `nodeId`/`role`/`metadata` 透传到 `AgentEvent`，使每条事件可定位到所属节点。前端据此能渲染"哪个角色在做哪件事"的进度条。`AutonomousExecutor` 在目标循环中额外发射 `goal_start`/`goal_decomposed`/`reflection`/`goal_complete` 四类事件，构成"目标级"进度视图。详见 [07 编排引擎](07-orchestration.md)。

## 13.9 事件流典型时序

```
单 Agent（ReAct 直连）:
  status → thinking → content → done

带工具的 ReAct:
  status → thinking → tool_call → tool_executing → tool_result →
  thinking → content → done

Pipeline 编排:
  graph_start → node_start → (ReAct 子流) → node_complete →
  node_start → ... → graph_complete

自主目标:
  goal_start → goal_decomposed → graph_start → node_* → graph_complete →
  [验收不过] reflection → goal_decomposed → ... → goal_complete(SUCCESS/FAILED)

Debate 编排:
  graph_start → 并行 node_start(D)...node_complete →
  node_start(JUDGE) → ... → node_complete → graph_complete

带 HITL:
  ... → node_start(HUMAN) → approval_required →
  [人工决策] approval_result → node_complete → ...
```

## 13.10 小结

`AgentEvent` 是引擎对外的统一观察面：8 种 ReAct 事件覆盖单 Agent 推理与工具调用全链路，编排层扩展事件覆盖图/节点/交接/审批/目标全链路，通过 `nodeId`/`role`/`metadata` 实现可定位、可结构化的进度表达。SSE 传输格式简单（`data: <json>\n\n`），与 Spring MVC `StreamingResponseBody` 及前端 `fetch + ReadableStream` 自然契合。使用方据此可构建从终端流式输出到多 Agent 协作仪表盘的各类实时 UI，而无需关心底层 Reactor 细节。