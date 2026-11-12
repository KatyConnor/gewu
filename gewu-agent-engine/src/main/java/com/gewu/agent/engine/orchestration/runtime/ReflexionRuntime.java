package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.cognition.ReasoningResult;
import com.gewu.agent.engine.cognition.ReflectionEngine;
import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Reflexion 运行时 - 执行 ReAct 后 Critic 评估，不达标则反思 + 注入改进重跑（AE-P1-8/D-5，限 N 轮）。
 * <p>闭环（43 号预承诺规则的"接线"分支动作）：ReAct 执行 → Critic 评估（验收标准=原始任务描述）
 * → 通过则采纳产出 → 不通过则反思产出改进建议注入重跑（≤maxReflectionRounds 轮）
 * → 轮次耗尽如实暴露 ERROR（不静默伪装成功）。Critic/反思能力由使用方经
 * {@link ReasoningKernel}/{@link ReflectionEngine} SPI 提供；未提供时保持透传旧行为
 * （等效单次 ReAct，与"未提供时回退单次 ReAct"文档约定一致）。
 * <p>运行时适配（AE-P1-1 同批）：模型/模式字段与入口调度指令透传到轮任务。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-08-20
 */
@Slf4j
public class ReflexionRuntime implements AgentRuntime {

    private final AgentExecutor executor;
    /** 认知内核（Critic 评估；null=未接认知，保持透传旧行为） */
    private final ReasoningKernel reasoningKernel;
    /** 反思引擎（产出改进建议；null=用 Critic 结论作为改进注入） */
    private final ReflectionEngine reflectionEngine;
    /** 反思重试最大轮次（防止无限循环） */
    private final int maxReflectionRounds;

    public ReflexionRuntime(AgentExecutor executor) {
        this(executor, null, null, 3);
    }

    public ReflexionRuntime(AgentExecutor executor, int maxReflectionRounds) {
        this(executor, null, null, maxReflectionRounds);
    }

    public ReflexionRuntime(AgentExecutor executor, ReasoningKernel reasoningKernel,
                            ReflectionEngine reflectionEngine, int maxReflectionRounds) {
        this.executor = executor;
        this.reasoningKernel = reasoningKernel;
        this.reflectionEngine = reflectionEngine;
        this.maxReflectionRounds = Math.max(1, maxReflectionRounds);
    }

    @Override
    public Flux<AgentEvent> execute(AgentTask task) {
        return Flux.defer(() -> {
            if (reasoningKernel == null) {
                // 未接认知内核：保持透传旧行为（不伪造 Critic 结论）
                return executor.executeStream(task);
            }
            return Flux.concat(
                    Flux.just(AgentEvent.builder()
                            .type(AgentEvent.STATUS)
                            .content("Reflexion 执行中...")
                            .metadata(Map.of("maxRounds", maxReflectionRounds))
                            .build()),
                    attempt(task, 0, null));
        }).doOnError(e -> log.error("Reflexion 异常: agentId={}", task.getAgentId(), e));
    }

    /** 单轮尝试：ReAct 执行 → Critic 评估 → 通过则结束；不通过则反思注入重跑 */
    private Flux<AgentEvent> attempt(AgentTask task, int round, String reflectionAdvice) {
        AgentTask attemptTask = (round == 0 || reflectionAdvice == null || reflectionAdvice.isBlank())
                ? task : injectReflection(task, reflectionAdvice, round);
        StringBuilder content = new StringBuilder();
        return executor.executeStream(attemptTask)
                .doOnNext(event -> {
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        content.append(event.getContent());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String output = content.toString();
                    if (output.isBlank()) {
                        // 无产出：无从评估，保持流结束（失败面由执行器自身的 error 事件承载）
                        return Flux.empty();
                    }
                    // Critic 评估（验收标准=原始任务描述）
                    ReasoningResult critique = reasoningKernel.critique(output, List.of(task.getMessage()));
                    log.info("Reflexion Critic: round={}, score={}, accepted={}",
                            round, critique.getScore(), critique.isAccepted());
                    if (critique.isAccepted()) {
                        return Flux.empty(); // 评估通过，采纳产出（流自然结束）
                    }
                    if (round >= maxReflectionRounds) {
                        // 轮次耗尽：如实暴露（不静默伪装成功）
                        return Flux.just(AgentEvent.builder()
                                .type(AgentEvent.ERROR)
                                .errorMessage(String.format(
                                        "Reflexion 反思轮次耗尽（rounds=%d），critic 评分 %.2f 未达标，保留最后一轮产出",
                                        maxReflectionRounds, critique.getScore()))
                                .metadata(Map.of("rounds", round, "criticScore", critique.getScore()))
                                .build());
                    }
                    // 反思：改进建议注入下一轮（ReflectionEngine 未提供时用 Critic 结论）
                    String advice = reflectionEngine != null
                            ? reflectionEngine.reflect(task.getSessionId() != null
                                    ? task.getSessionId() : "reflexion", output, task.getMessage())
                            : critique.getVerdict();
                    return Flux.just(
                                    AgentEvent.builder()
                                            .type(AgentEvent.REFLECTION)
                                            .metadata(Map.of(
                                                    "round", round,
                                                    "criticScore", critique.getScore(),
                                                    "advice", advice != null ? advice : ""))
                                            .build(),
                                    AgentEvent.builder()
                                            .type(AgentEvent.STATUS)
                                            .content("第 " + (round + 1) + " 轮反思重试（Critic 未通过）")
                                            .build())
                            .concatWith(Flux.defer(() -> attempt(task, round + 1, advice)));
                }));
    }

    /** 改进建议注入：前缀指令进入轮任务上下文（不覆盖原始任务语义） */
    private AgentTask injectReflection(AgentTask task, String advice, int round) {
        String injected = "【第 " + round + " 轮反思改进指令（上一轮产出未通过 Critic 评估）】\n"
                + (advice == null || advice.isBlank() ? "针对验收标准改进产出质量。" : advice)
                + "\n\n【原始任务】\n" + task.getMessage();
        return AgentTask.builder()
                .agentId(task.getAgentId())
                .sessionId(task.getSessionId())
                .userId(task.getUserId())
                .message(injected)
                // 运行时适配（AE-P1-1 同批）：模型/模式字段透传
                .modelProvider(task.getModelProvider())
                .modelName(task.getModelName())
                .agentMode(task.getAgentMode())
                .thinkingStyle(task.getThinkingStyle())
                .modelRouteEnabled(task.getModelRouteEnabled())
                .entryDirectives(task.getEntryDirectives())
                .entryDirectivesSource(task.getEntryDirectivesSource())
                .entryDirectivesMeta(task.getEntryDirectivesMeta())
                .build();
    }
}
