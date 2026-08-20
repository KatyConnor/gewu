# 格物 Agent 引擎 — 核心引擎

> 本篇详解 `core` 包：`AgentEngine` 门面、`AgentExecutor` 接口、`ReactAgentExecutor` 的 ReAct 循环（同步/流式/工具并行/递归轮次）、`AgentTask`、`AgentEvent` 事件协议、`AgentEngineConfig` 配置项。
> 包：`com.gewu.agent.engine.core`（事件在 `core.event`）。

## 1. AgentEngine 门面

门面只有一个职责：对外暴露单一入口，对内委托 `AgentExecutor`。其设计刻意保持极薄，便于上层（如编排层 `Orchestrator`）直接持有 `AgentExecutor` 而绕过门面。

```java
package com.gewu.agent.engine.core;

import reactor.core.publisher.Flux;

public class AgentEngine {
    private final AgentExecutor executor;

    public AgentEngine(AgentExecutor executor) { this.executor = executor; }

    /** 同步执行 Agent 任务 */
    public LlmResponse execute(AgentTask task) { return executor.execute(task); }

    /** 流式执行 Agent 任务 */
    public Flux<AgentEvent> executeStream(AgentTask task) { return executor.executeStream(task); }

    /** 获取底层执行器（供编排层组合） */
    public AgentExecutor getExecutor() { return executor; }
}
```

使用方式：

```java
@Autowired private AgentEngine agentEngine;

// 同步
LlmResponse response = agentEngine.execute(AgentTask.builder()
        .agentId("dev-001").sessionId("sess-1").userId("u1")
        .modelProvider("deepseek").modelName("deepseek-chat")
        .message("帮我查一下订单 #1024 的状态").build());

// 流式
agentEngine.executeStream(task).subscribe(event -> {
    switch (event.getType()) {
        case AgentEvent.THINKING      -> renderThinking(event.getReasoning());
        case AgentEvent.CONTENT       -> appendContent(event.getContent());
        case AgentEvent.TOOL_CALL     -> showToolCall(event.getToolCall());
        case AgentEvent.TOOL_RESULT   -> showToolResult(event.getToolResult());
        case AgentEvent.DONE          -> finish();
        case AgentEvent.ERROR         -> showError(event.getErrorMessage());
    }
});
```

## 2. AgentExecutor 接口

```java
public interface AgentExecutor {
    LlmResponse       execute(AgentTask task);        // 同步阻塞
    Flux<AgentEvent>  executeStream(AgentTask task);  // 流式增量
}
```

框架默认提供 `ReactAgentExecutor`。使用方也可实现此接口提供自定义策略（Plan-Execute / Reflexion 等），编排层的 `runtime` 子包即提供了对应运行时封装（`PlanExecuteRuntime` / `ReflexionRuntime`）。

## 3. ReactAgentExecutor 的 ReAct 循环

核心循环：**LLM 推理 → 若请求工具则并行执行 → 结果回灌 → 继续推理**，直到 LLM 不再请求工具或达到 `maxToolRounds`。

构造依赖（`@RequiredArgsConstructor` 注入）：

```java
public class ReactAgentExecutor implements AgentExecutor {
    private final LlmClientRegistry     llmClientRegistry;
    private final ToolExecutor          toolExecutor;
    private final MessageBuilder       messageBuilder;
    private final SessionContextService sessionContextService;  // SPI
    private final PersistenceService   persistenceService;      // SPI
    private final AgentEngineConfig    config;
    private final ObjectMapper         objectMapper;
}
```

### 3.1 同步执行流程

```
        ┌─ round = 0 ──────────────────────────────────────┐
        ▼                                                │
   构造 LlmRequest ──> LlmClient.chat ──> LlmResponse      │
        │                                                │
        │  toolCalls 为空? ──是──> 返回 response            │
        │  否                                              │
        ▼                                                │
   追加 assistant 消息(content)                            │
   对每个 toolCall 启动 CompletableFuture.supplyAsync       │
   (提交到 config.toolExecutor 线程池)                     │
        │                                                │
   CompletableFuture.allOf(...).join()                    │
        │                                                │
   对每个结果追加 role=tool 消息(toolCallId/name/content)   │
        │                                                │
   round++  ─────────────────────────────────────────────┘
        │
   round >= maxToolRounds ?  ──> throw AgentEngineException("TOOL_ROUNDS_EXCEEDED")
```

关键同步代码片段（节选自源码）：

```java
for (int round = 0; round < config.getMaxToolRounds(); round++) {
    LlmRequest llmRequest = LlmRequest.builder()
            .model(pm[1]).messages(messages)
            .tools(tools.isEmpty() ? null : tools)
            .temperature(temperature).maxTokens(resolveMaxTokens(task))
            .stream(false).build();
    LlmResponse response = client.chat(llmRequest);

    // 无工具调用 → 直接返回最终回复
    if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
        return response;
    }

    // 追加 assistant 轮次
    messages.add(Message.builder().role("assistant").content(response.getContent()).build());

    // 工具并行执行
    List<CompletableFuture<ToolResult>> futures = response.getToolCalls().stream()
            .map(tc -> CompletableFuture.supplyAsync(
                    () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                              toolConfigMap.get(tc.getName())),
                    config.getToolExecutor()))
            .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // 结果回灌为 role=tool 消息
    for (int i = 0; i < response.getToolCalls().size(); i++) {
        ToolCall toolCall = response.getToolCalls().get(i);
        ToolResult result = futures.get(i).join();
        String output = result.isSuccess() ? result.getOutput() : result.getError();
        messages.add(Message.builder()
                .role("tool").content(output)
                .toolCallId(toolCall.getId()).name(toolCall.getName()).build());
    }
}
throw AgentEngineException.of("TOOL_ROUNDS_EXCEEDED", "工具调用轮次超限");
```

要点：**工具并行**通过 `CompletableFuture.supplyAsync(..., config.getToolExecutor())` 实现，全部提交后用 `allOf().join()` 等齐再回灌；回灌顺序与 LLM 给出的 `toolCalls` 顺序一致，每条 `tool` 消息都带 `toolCallId` 关联，保证 LLM 能正确对应结果。

### 3.2 流式执行流程

流式实现是 `executeStream` 委托 `streamRound`，后者用 Reactor 的 `concatWith` 把"LLM 流 → 工具执行 → 下一轮"递归串联：

```
Flux.defer( streamRound(round=0) )
  .onErrorResume( -> ERROR 事件 )

streamRound(round):
  chatStream(LlmRequest)  ->  map chunk:
                               reasoning? -> THINKING 事件
                               delta?     -> 累积 + CONTENT 事件
                               toolCallDelta? -> 累积入 ToolCallAccumulator
                               finishReason? -> 记录
                             filter(type != null)
                             startWith(STATUS "正在思考..."/"正在继续推理...")
                             concatWith( 流结束处理:
                                assembleToolCalls(累积器)
                                追加 assistant 消息(content)
                                toolCalls 空?
                                   finish=length 且 content 空? -> ERROR(被截断)
                                   否则 -> DONE
                                否则: 发 TOOL_CALL* -> TOOL_EXECUTING*
                                      flatMap 并行执行 -> TOOL_RESULT*
                                      concatWith( streamRound(round+1) )
                             )
```

流式中的 tool 事件与并行执行片段：

```java
return Flux.fromIterable(toolCallEvents)
        .concatWith(Flux.fromIterable(executingEvents))
        .concatWith(Flux.fromIterable(toolCalls)
                .flatMap(tc -> Mono.fromFuture(CompletableFuture.supplyAsync(
                        () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                toolConfigMap.get(tc.getName())),
                        config.getToolExecutor()))
                        .map(result -> {
                            String output = result.isSuccess() ? result.getOutput() : result.getError();
                            synchronized (messages) {
                                messages.add(Message.builder().role("tool").content(output)
                                        .toolCallId(tc.getId()).name(tc.getName()).build());
                            }
                            return AgentEvent.builder()
                                    .type(AgentEvent.TOOL_RESULT)
                                    .toolResult(AgentEvent.ToolResultInfo.builder()
                                            .toolCallId(tc.getId()).name(tc.getName()).result(output).build())
                                    .build();
                        }))
                .concatWith(Flux.defer(() ->
                        streamRound(client, model, messages, tools, toolConfigMap,
                                toolContext, temperature, round + 1))));
```

要点：
- 工具调用增量通过 `ToolCallAccumulator` 按 `id`（无 id 用 `"default"`）累积 `name` 与 `arguments`，流结束后 `assembleToolCalls` 组装成完整 `ToolCall` 列表。
- `flatMap` 让多个工具并发执行；`messages` 在并发回灌时用 `synchronized` 保护。
- `concatWith(Flux.defer(... streamRound(round+1)))` 实现"工具结果就绪后自动进入下一轮推理"的递归。
- 截断保护：若 `finish_reason=length` 且 `content` 为空，说明推理 token 用尽未产出正文，直接发 `ERROR` 事件提示"增大 max_tokens 或简化问题"。

### 3.3 工具并行与递归轮次说明

| 维度 | 实现 |
|------|------|
| 并行度 | 受 `AgentEngineConfig.toolExecutor` 线程池限流（默认核心8/最大16/队列100，`CallerRunsPolicy`）|
| 回灌顺序 | 按 LLM 返回的 `toolCalls` 顺序追加 `role=tool` 消息，每条带 `toolCallId` |
| 递归终止 | 无工具调用 → DONE；达到 `maxToolRounds` → ERROR |
| 同步 vs 流式递归 | 同步用 `for` 循环；流式用 `concatWith(Flux.defer(...))` 递归 |

## 4. AgentTask 字段说明

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentTask {
    private String           agentId;          // Agent 标识(直接对话可为空)
    private String           sessionId;       // 会话标识
    private String           userId;          // 操作用户
    private String           message;         // 用户消息
    private List<Message>    history;         // 历史消息(空则从 SessionContextService 加载)
    private String           modelProvider;   // LLM 供应商(显式 > AgentSpec)
    private String           modelName;       // 模型名(显式 > AgentSpec)
    private String           agentMode;       // assistant/expert/creative/precise
    private String           thinkingStyle;   // chain-of-thought/tree-of-thought/react/step-by-step/socratic
    private Double           temperature;     // 覆盖模式默认温度
    private Integer          maxTokens;       // 覆盖默认上限
}
```

解析优先级（源码 `resolveProviderAndModel`）：

1. `task.modelProvider` + `task.modelName` 都非空 → 用之
2. 否则用 `PersistenceService.loadAgent(agentId)` 返回的 `AgentSpec` 的 provider/model
3. 都解析不到 → `throw AgentEngineException.of("MODEL_NOT_RESOLVED", ...)`

温度解析（`resolveTemperature`）：优先 `task.temperature`，否则 `PromptDirective.getTemperatureByMode(task.agentMode)`：`expert=0.3` / `creative=0.9` / `precise=0.1` / 默认 `0.7`。

历史消息：`task.history` 为 null 且 `sessionId` 非空时，由 `sessionContextService.buildContextMessages(sessionId, config.defaultHistoryLimit)` 加载（默认上限 50 条）。

## 5. AgentEvent 事件协议

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentEvent {
    private String            type;          // 事件类型
    private String            content;       // 正文增量(status/content)
    private String            reasoning;     // 推理内容(thinking)
    private ToolCallInfo      toolCall;      // tool_call/tool_executing
    private ToolResultInfo    toolResult;    // tool_result
    private String            errorMessage;  // error
    // 编排层扩展
    private String            nodeId;
    private String            role;
    private Map<String,Object> metadata;

    public static class ToolCallInfo   { String id; String name; String arguments; }
    public static class ToolResultInfo { String toolCallId; String name; String result; }

    // 常量
    public static final String STATUS="status", THINKING="thinking", CONTENT="content",
        TOOL_CALL="tool_call", TOOL_EXECUTING="tool_executing", TOOL_RESULT="tool_result",
        DONE="done", ERROR="error";
}
```

事件类型总表（`type` 字段）：

| 类别 | 事件类型 | 说明 | 主要字段 |
|------|----------|------|----------|
| ReAct | `status` | 状态提示（正在思考…） | content |
| ReAct | `thinking` | 推理/思考增量 | reasoning |
| ReAct | `content` | 正式回复增量 | content |
| ReAct | `tool_call` | 工具调用决策 | toolCall(id,name,args) |
| ReAct | `tool_executing` | 工具执行中 | toolCall |
| ReAct | `tool_result` | 工具执行结果 | toolResult(toolCallId,name,result) |
| ReAct | `done` | 执行完成 | — |
| ReAct | `error` | 执行错误 | errorMessage |
| 编排 | `graph_start` / `node_start` / `node_complete` / `handoff` / `goal_decomposed` / `reflection` / `goal_start` / `goal_complete` / `graph_complete` | 多 Agent 编排事件 | nodeId / role / metadata |

`content` 事件仅携带本帧增量（非累积），使用方需自行拼接；`thinking` 与 `content` 由 `reasoning_content` 与 `content` 字段分离而来（见 LLM 层分离逻辑）。

## 6. AgentEngineConfig 配置项

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentEngineConfig {
    private int            maxToolRounds;       // 单次执行最大工具调用轮次
    private int            defaultMaxTokens;    // 默认 max_tokens 上限
    private double         defaultTemperature;  // 默认采样温度
    private ExecutorService toolExecutor;       // 工具并行线程池
    private int            defaultHistoryLimit; // 会话历史默认加载条数
}
```

自动装配中（`agentEngineConfig` Bean）的默认取值来源于 `AgentEngineProperties.Engine`：

| 配置项 | 属性键 | 默认 |
|--------|--------|------|
| `maxToolRounds` | `agent.engine.engine.max-tool-rounds` | 10 |
| `defaultMaxTokens` | `agent.engine.engine.default-max-tokens` | 8192 |
| `defaultTemperature` | `agent.engine.engine.default-temperature` | 0.7 |
| `toolExecutor` | — | `ThreadPoolExecutor`（核心8/最大16/队列100，`CallerRunsPolicy`，daemon 线程 `agent-tool-exec`）|
| `defaultHistoryLimit` | — | 50（装配时硬编码）|

`resolveMaxTokens` 逻辑：`task.maxTokens > 0` 用之，否则 `config.defaultMaxTokens`。

错误码约定：框架统一抛 `AgentEngineException.of(code, message)`，核心引擎层常用码：`TOOL_ROUNDS_EXCEEDED` / `MODEL_NOT_RESOLVED`。

## 7. 小结

`core` 包是引擎的"引擎"：把"消息构建 → LLM 推理 → 工具并行执行 → 结果回灌 → 递归轮次"这五件事编成一个可同步、可流式、可轮次上限保护的 ReAct 循环，外层只暴露 `AgentEngine` 一个门面。可替换点集中在 `AgentExecutor` 接口（换执行策略）与各 `*Service` SPI（换数据来源），是后续 LLM 层、工具层、编排层的共同基座。

---

上一篇：[02-architecture.md](./02-architecture.md) ｜ 下一篇：[04-llm-layer.md](./04-llm-layer.md)