# 08 · 人机协同 (HITL)

> Agent 自主执行降低人力成本，但关键决策、高风险操作、边界不清晰环节仍需人工把关。格物 Agent 引擎在 `com.gewu.agent.engine.hitl` 提供人机协同（Human-In-The-Loop）SPI 与模型：把"请求人审/接管/回退"抽象为几个接口与 DTO，由使用方对接审批队列、IM 通知或 Webhook 等渠道。本篇是 HITL 的完整参考。

## 8.1 为什么需要 HITL

完全自主的 Agent 在生产环境不可接受的风险点包括：部署到生产、删除数据、产生不可逆结构变更、越权调用工具、产出未达验收标准。HITL 让人工在关键节点介入，既保留 Agent 的自动化能力，又守住安全底线。

```
   全自动:  Agent ──执行──► 不可逆操作 ──► 事故风险
   带 HITL: Agent ──执行──► [HUMAN 节点] ──请求──► 人工 ──决策──► 继续/回退/接管
```

## 8.2 架构总览

```
   OrchestrationGraph
        │ GraphNode.type=HUMAN
        ▼
   HumanNode 执行逻辑(使用方扩展)
        │ 构造 ApprovalRequest
        ▼
   HitlGateway.requestApproval(request) : Mono<HumanDecision>
        │                                          ▲
        │  阻塞节点(异步)                          │ 异步恢复
        ▼                                          │
   ┌─────────────────────┐    推送通知     ┌───────┴──────────┐
   │  使用方适配层         │ ────────────► │ 审批队列/IM/Webhook│
   │  - 持久化 ApprovalRequest                │  - 人选 & 决策     │
   │  - 发起 HTTP/Webhook/IM 通知           │  - 提交 HumanDecision│
   │  - 等待 Mono 被完成                    └──────────────────┘
   └─────────────────────┘
        │ submitDecision(approvalId, decision)
        ▼
   Mono<HumanDecision> 完成 ──► 节点恢复执行(继续/回退/终止)
```

## 8.3 HitlGateway SPI

```java
package com.gewu.agent.engine.hitl;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface HitlGateway {

    /** 请求人工审批。阻塞当前节点，异步等待决策。返回 Mono 在 submitDecision 到达时完成。 */
    Mono<HumanDecision> requestApproval(ApprovalRequest request);

    /** 提交人工决策（恢复执行） */
    void submitDecision(String approvalId, HumanDecision decision);

    /** 人工接管（直接控制执行） */
    default void takeover(String executionId, String operatorId) { }

    /** 人工回退到某节点 */
    default void rollback(String executionId, String toNodeId) { }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `requestApproval` 返回 `Mono<HumanDecision>`，代表"等待人工决策"的异步结果；`submitDecision` 在外部决策到达时调用以完成该 `Mono`；`takeover`/`rollback` 是默认空实现，使用方按需实现 |
| **阻塞模型** | `Mono` 不阻塞线程，编排图节点通过订阅该 `Mono` 实现"暂停等待"语义，决策到达即恢复 |
| **实现要求** | 使用方需维护 `approvalId → Mono sink` 的映射，`requestApproval` 创建并登记 sink，`submitDecision` 取出 sink 并 `sink.success(decision)`；同时负责把 `ApprovalRequest` 推送到审批渠道并等待人工回填 |
| **NoOp 默认** | `NoOpHitlGateway`：`requestApproval` 立即返回 `HumanDecision{decision="APPROVED", operatorId="system"}`，`submitDecision` 空操作 —— 即"不阻塞、直接批准"，适用于无 HITL 场景 |
| **业务适配备注** | 格物平台对接审批中心 + IM 通知（飞书/钉钉），`approvalId` 关联 `approval_record` 表，`operatorId` 取当前登录用户 |

`AgentEngineAutoConfiguration` 已装配 `NoOpHitlGateway` Bean，使用方注册任意 `HitlGateway` 实现即可覆盖。

## 8.4 ApprovalRequest / HumanDecision 模型

### ApprovalRequest

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApprovalRequest {
    private String approvalId;        // 审批 ID
    private String executionId;       // 执行实例 ID
    private String nodeId;            // 节点 ID
    private String type;              // APPROVE_REJECT / INPUT / SELECT / EDIT
    private String summary;           // Agent 产出摘要
    private Object artifact;          // 待审产物（代码 / 文档 / 部署计划）
    private java.util.List<String> options;  // 选项列表（SELECT 类型）
    private Integer timeoutSeconds;   // 超时（秒）
}
```

### HumanDecision

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HumanDecision {
    private String decision;     // APPROVED / REJECTED / INPUT_VALUE / SELECTED / EDITED
    private String value;        // 决策内容（输入文本 / 选择项 / 修改后产物）
    private String operatorId;   // 操作人
}
```

`ApprovalRequest.type` 与匹配的 `HumanDecision.decision`：

| ApprovalRequest.type | 语义 | 期望 HumanDecision.decision |
|----------------------|------|----------------------------|
| `APPROVE_REJECT` | 审批门：通过/驳回 | `APPROVED` / `REJECTED` |
| `INPUT` | 输入门：补充信息 | `INPUT_VALUE`，`value` 带文本 |
| `SELECT` | 选择门：多选一 | `SELECTED`，`value` 带选中项 |
| `EDIT` | 协同编辑：修改产物 | `EDITED`，`value` 带修改后产物 |

## 8.5 六种协同模式

| 模式 | 触发方式 | ApprovalRequest.type | 人工动作 | 结果 |
|------|----------|----------------------|----------|------|
| 审批门（APPROVE_REJECT） | `HUMAN` 节点配置 | `APPROVE_REJECT` | 通过/驳回 | 通过→继续下游；驳回→终止或回退 |
| 输入门（INPUT） | 信息不足时阻塞 | `INPUT` | 补充文本 | `value` 注入上下文继续执行 |
| 协同编辑（EDIT） | 产出需人工修订 | `EDIT` | 修改产物 | 以修订后 `value` 替换 Agent 产出 |
| 实时接管（takeover） | 异常或人工主动介入 | — | 调 `takeover(executionId, operatorId)` | 切换为人工直接控制后续执行 |
| 回退重做（rollback） | 产出不达标 | — | 调 `rollback(executionId, toNodeId)` | 重新执行某历史节点 |
| 异步通知（NOTIFY） | 需告知但不阻塞 | 任意 | 仅推送，不阻塞图执行 | 图继续；通知供人参考 |

## 8.6 HumanNode 实现思路

框架未内置 `HumanNode` 执行器（其扩展点交由使用方通过 SPI 实现），标准实现思路如下：

```
HumanNode.execute(node, ctx):
   1. 根据 GraphNode.config 构造 ApprovalRequest:
      type = config.get("type")        // APPROVE_REJECT / INPUT / SELECT / EDIT
      summary = 上游节点产出的摘要
      artifact = 上游节点产出原文
      options = config.get("options")  // SELECT 类型
      timeoutSeconds = config.get("timeoutSeconds")
   2. approvalId = UUID
   3. Mono<HumanDecision> mono = hitlGateway.requestApproval(request)
   4. 发射 AgentEvent(type="approval_required", metadata={approvalId, type, summary})
   5. 订阅 mono:
      - APPROVED   → 发射 approval_result(approved) → 继续下游
      - REJECTED   → 发射 approval_result(rejected) → 终止或按 edge.condition 路由
      - INPUT_VALUE/SELECTED/EDITED → 把 value 写入 ctx.variables → 继续
      - 超时       → 默认按 REJECTED 处理（使用方在 requestApproval 内实现超时）
```

编排事件 `approval_required`（请求审批）与 `approval_result`（决策结果）属于编排层扩展事件（见 [13 事件协议](13-event-protocol.md)）。

## 8.7 NoOp 默认行为

```java
public class NoOpHitlGateway implements HitlGateway {
    @Override
    public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
        return Mono.just(HumanDecision.builder()
                .decision("APPROVED")
                .operatorId("system")
                .build());
    }
    @Override
    public void submitDecision(String approvalId, HumanDecision decision) { /* NoOp */ }
}
```

行为：任何审批请求即时自动通过，操作人记为 `system`，不阻塞、不发送通知。这意味着：未对接审批渠道时，编排图中的 `HUMAN` 节点等价于"直通门"，整个流程可全自动跑通——这是框架"零业务实现即可运行"原则的体现。

## 8.8 使用方对接示例

### 8.8.1 审批队列 + Mono sink 映射

```java
@Component
public class QueueHitlGateway implements HitlGateway {

    private final ApprovalQueueService queueService;   // 使用方审批队列
    private final Map<String, reactor.core.publisher.MonoSink<HumanDecision>> pending
            = new ConcurrentHashMap<>();

    public QueueHitlGateway(ApprovalQueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
        return Mono.<HumanDecision>create(sink -> {
            pending.put(request.getApprovalId(), sink);
            queueService.enqueue(request);              // 推送到审批队列
            if (request.getTimeoutSeconds() != null) {
                sink.onTimeout(Duration.ofSeconds(request.getTimeoutSeconds()),
                        () -> { pending.remove(request.getApprovalId());
                                sink.success(HumanDecision.builder()
                                    .decision("REJECTED").operatorId("system").build()); });
            }
        });
    }

    @Override
    public void submitDecision(String approvalId, HumanDecision decision) {
        var sink = pending.remove(approvalId);
        if (sink != null) {
            sink.success(decision);                     // 恢复被阻塞的节点
        }
    }

    @Override
    public void takeover(String executionId, String operatorId) {
        // 标记执行为人工接管，外部调度器据此暂停 Agent 自动推进
    }

    @Override
    public void rollback(String executionId, String toNodeId) {
        // 通知 Orchestrator 重置 currentNodeId 并重新调度
    }
}
```

### 8.8.2 IM 通知（飞书/钉钉）

```java
@Override
public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
    String text = "Agent 审批请求\n id=%s\n type=%s\n 摘要:%s".formatted(
            request.getApprovalId(), request.getType(), request.getSummary());
    imClient.sendCard("approval-group", text, request.getOptions());   // 推送审批卡片
    return Mono.<HumanDecision>create(sink -> pending.put(request.getApprovalId(), sink));
}
// 人工在 IM 卡片点击后，回调服务调用 gateway.submitDecision(id, decision)
```

### 8.8.3 Webhook（外部系统审批）

```java
@Override
public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
    return Mono.<HumanDecision>create(sink -> {
        pending.put(request.getApprovalId(), sink);
        String payload = objectMapper.writeValueAsString(request);
        httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(callbackUrl))            // 外部审批系统回调端点
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
    });
}
// 外部系统审批完成后回调 POST /hitl/decision {approvalId, decision, value, operatorId}
// → gateway.submitDecision(...)
```

## 8.9 与编排图集成

在编排图中声明 `HUMAN` 节点并配 `type`，即可在流程中插入审批环节：

```java
GraphNode human = GraphNode.builder()
    .nodeId("approve-arch")
    .type(NodeType.HUMAN)
    .refId("approval-config-001")
    .config(Map.of(
        "type", "APPROVE_REJECT",
        "summary", "架构设计评审",
        "timeoutSeconds", 3600))
    .build();

// 路由：通过继续开发；驳回回到需求节点
GraphEdge.builder().fromNode("n2").toNode("approve-arch").build(),
GraphEdge.builder().fromNode("approve-arch").toNode("n4")
    .condition("decision == 'APPROVED'").build(),
GraphEdge.builder().fromNode("approve-arch").toNode("n1")
    .condition("decision == 'REJECTED'").build()
```

`AutonomousExecutor` 把"HITL 强制节点"列为六重防失控边界之一：关键阶段的人工介入避免 Agent 在不可逆操作上失控（见 [07 编排引擎](07-orchestration.md#79-autonomousexecutor-自主目标循环)）。

## 8.10 小结

HITL SPI 把"请求人审—等待决策—恢复执行"抽象为 `Mono<HumanDecision>` 的异步模型，与 Reactor 流式架构天然契合。`NoOp` 默认让无 HITL 场景零成本运行；对接审批队列、IM、Webhook 只需实现 `requestApproval`/`submitDecision` 两个方法即可。配合编排图的 `HUMAN` 节点与 `approval_required`/`approval_result` 事件，使用方既能构建全自动流程，也能在任意节点插入人工闸门、输入补充、协同编辑、实时接管与回退重做。