package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debate 模式 - 多个 Agent 持不同立场/方案辩论，由裁判 Agent 汇总最优方案。
 * <p>所有方案 Agent 并行辩论 -> 裁判 Agent 接收全部方案后裁决。
 * 适用于架构选型、技术方案评审等多视角权衡的高风险决策。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class DebateModeHandler implements ModeHandler {

    private final AgentExecutor executor;

    @Override
    public String mode() {
        return "DEBATE";
    }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder()
                    .type("graph_start")
                    .metadata(Map.of("executionId", ctx.getExecutionId(), "mode", "DEBATE"))
                    .build());

            // 筛选辩论 Agent 节点（type=AGENT）和裁判节点（type=ROUTER/MERGE）
            var allNodes = graph.getNodes() == null ? List.<com.gewu.agent.engine.orchestration.model.GraphNode>of()
                    : graph.getNodes();
            var debaters = allNodes.stream()
                    .filter(n -> n.getType() == com.gewu.agent.engine.orchestration.model.NodeType.AGENT)
                    .toList();
            var judge = allNodes.stream()
                    .filter(n -> n.getType() == com.gewu.agent.engine.orchestration.model.NodeType.MERGE
                            || n.getType() == com.gewu.agent.engine.orchestration.model.NodeType.ROUTER)
                    .findFirst().orElse(null);

            if (debaters.isEmpty()) {
                sink.next(AgentEvent.builder().type("graph_complete")
                        .metadata(Map.of("status", "SUCCESS")).build());
                sink.complete();
                return;
            }

            String topic = (String) ctx.getVariable("input");
            var proposals = new java.util.concurrent.CopyOnWriteArrayList<String>();

            // 并行辩论（各方案 Agent 独立产出）
            var debateFlux = Flux.fromIterable(debaters)
                    .flatMap(node -> {
                        sink.next(AgentEvent.builder()
                                .type("node_start").nodeId(node.getNodeId()).role(node.getRoleCode())
                                .metadata(Map.of("phase", "DEBATE"))
                                .build());
                        var accumulated = new StringBuilder();
                        var failed = new java.util.concurrent.atomic.AtomicBoolean(false);
                        AgentTask task = AgentTask.builder()
                                .agentId(node.getRefId())
                                .sessionId(ctx.getSessionId())
                                .userId(ctx.getUserId())
                                .message(topic)
                                .build();
                        return executor.executeStream(task)
                                .doOnNext(event -> {
                                    // 失败传播（docs/design/47 问题一）：辩手失败记占位，不影响其他辩手与裁判
                                    if (AgentEvent.ERROR.equals(event.getType())) {
                                        failed.set(true);
                                    }
                                    sink.next(AgentEvent.builder()
                                            .type(event.getType()).content(event.getContent())
                                            .reasoning(event.getReasoning())
                                            .toolCall(event.getToolCall()).toolResult(event.getToolResult())
                                            .errorMessage(event.getErrorMessage())
                                            .nodeId(node.getNodeId()).role(node.getRoleCode())
                                            .metadata(Map.of("phase", "DEBATE"))
                                            .build());
                                    if ("content".equals(event.getType()) && event.getContent() != null) {
                                        accumulated.append(event.getContent());
                                    }
                                })
                                .doFinally(sig -> {
                                    if (failed.get()) {
                                        // 失败辩手：以占位提案参与汇总，不发 node_complete（避免失败伪装成完成）
                                        proposals.add("[辩手 " + node.getNodeId() + " 执行失败，无有效提案]");
                                        return;
                                    }
                                    proposals.add(accumulated.toString());
                                    sink.next(AgentEvent.builder().type("node_complete")
                                            .nodeId(node.getNodeId()).role(node.getRoleCode())
                                            .metadata(Map.of("phase", "DEBATE"))
                                            .build());
                                });
                    }, debaters.size());

            debateFlux.collectList().subscribe(
                    v -> {
                        if (judge == null) {
                            String combined = String.join("\n\n---\n\n", proposals);
                            sink.next(AgentEvent.builder()
                                    .type("graph_complete")
                                    .metadata(Map.of("status", "SUCCESS", "proposals", proposals.size(),
                                            "output", combined))
                                    .build());
                            sink.complete();
                        } else {
                            // 裁判
                            resolveJudge(executor, judge, proposals, ctx, sink);
                        }
                    },
                    sink::error);
        });
    }

    private void resolveJudge(AgentExecutor exec, com.gewu.agent.engine.orchestration.model.GraphNode judge,
                             java.util.List<String> proposals, OrchestrationContext ctx,
                             reactor.core.publisher.FluxSink<AgentEvent> sink) {
        sink.next(AgentEvent.builder().type("node_start")
                .nodeId(judge.getNodeId()).role(judge.getRoleCode())
                .metadata(Map.of("phase", "JUDGE"))
                .build());

        String joined = String.join("\n\n---方案分隔---\n\n", proposals);
        AgentTask judgeTask = AgentTask.builder()
                .agentId(judge.getRefId())
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .message("以下是多个候选方案，请综合评估选出最优方案并说明理由：\n\n" + joined)
                .build();

        var verdict = new StringBuilder();
        exec.executeStream(judgeTask).subscribe(
                event -> {
                    // 失败传播（docs/design/47 问题一）：裁判失败即整图 FAILED
                    if (AgentEvent.ERROR.equals(event.getType())) {
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.ERROR).errorMessage(event.getErrorMessage())
                                .nodeId(judge.getNodeId()).role(judge.getRoleCode())
                                .metadata(Map.of("phase", "JUDGE"))
                                .build());
                        sink.next(AgentEvent.builder().type("graph_complete")
                                .metadata(Map.of("status", "FAILED",
                                        "reason", judge.getNodeId() + " 裁决失败: "
                                                + (event.getErrorMessage() == null ? "未知错误" : event.getErrorMessage())))
                                .build());
                        sink.complete();
                        return;
                    }
                    sink.next(AgentEvent.builder()
                            .type(event.getType()).content(event.getContent())
                            .reasoning(event.getReasoning())
                            .errorMessage(event.getErrorMessage())
                            .nodeId(judge.getNodeId()).role(judge.getRoleCode())
                            .metadata(Map.of("phase", "JUDGE"))
                            .build());
                    if ("content".equals(event.getType()) && event.getContent() != null) {
                        verdict.append(event.getContent());
                    }
                },
                sink::error,
                () -> {
                    sink.next(AgentEvent.builder().type("node_complete")
                            .nodeId(judge.getNodeId()).role(judge.getRoleCode())
                            .metadata(Map.of("phase", "JUDGE"))
                            .build());
                    sink.next(AgentEvent.builder().type("graph_complete")
                            .metadata(Map.of("status", "SUCCESS",
                                    "verdict", verdict.toString(),
                                    "output", verdict.toString()))
                            .build());
                    sink.complete();
                });
    }
}