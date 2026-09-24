package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.budget.BudgetStatus;
import com.gewu.agent.engine.cognition.ComplexityRouter;
import com.gewu.agent.engine.hitl.UserInteractionGateway;
import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryRouter;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.agent.engine.message.MessageBuilder;
import com.gewu.agent.engine.message.PromptDirective;
import com.gewu.agent.engine.spi.AgentSpec;
import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.agent.engine.spi.SessionContextService;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.spi.TraceService;
import com.gewu.agent.engine.spi.MetricService;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.agent.engine.tool.security.OutputSanitizer;
import com.gewu.agent.engine.tool.security.PromptInjectionDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ReAct 执行器 - 框架默认 {@link AgentExecutor} 实现。
 * <p>核心循环：LLM 推理 -> 若请求工具则并行执行 -> 将结果回灌 -> 继续推理，直到 LLM 不再请求工具或达到轮次上限。
 * 支持同步与流式（{@link Flux}<{@link AgentEvent}>）两种模式，流式模式实时推送思考 / 内容 / 工具事件。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ReactAgentExecutor implements AgentExecutor {

    private final LlmClientRegistry llmClientRegistry;
    private final ToolExecutor toolExecutor;
    private final MessageBuilder messageBuilder;
    private final SessionContextService sessionContextService;
    private final PersistenceService persistenceService;
    private final AgentEngineConfig config;
    private final ObjectMapper objectMapper;
    private final MemoryRouter memoryRouter;
    private final MemoryStore memoryStore;
    private final BudgetController budgetController;
    private final PerceptionEngine perceptionEngine;
    private final ComplexityRouter complexityRouter;
    private final TraceService traceService;
    private final MetricService metricService;
    private final ResponseCache responseCache;
    private final PromptInjectionDetector promptInjectionDetector;
    private final OutputSanitizer outputSanitizer;
    private final ModelSelector modelSelector;
    /** 文件工作空间 SPI（S9 F3）：内置文件工具执行后端，null 时禁用文件工具 */
    private final com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace;
    /** 上下文压缩器 SPI（配额与上下文自治）：null 时上下文压缩禁用（仅告警） */
    private final com.gewu.agent.engine.spi.ContextCompactor contextCompactor;
    /** 模型上下文窗口提供者 SPI：null 时跳过压缩与输出上限校验 */
    private final com.gewu.agent.engine.spi.ModelContextProvider modelContextProvider;

    /** 用户交互网关（HITL 问答）：ask_user 工具的挂起-恢复后端；未装配时交互通道降级 */
    private final com.gewu.agent.engine.hitl.UserInteractionGateway userInteractionGateway;

    /** 推理模型截断自愈：finish=length 时的最大重试次数（8192 起步 ×3 次翻倍可达 65536 硬顶） */
    private static final int MAX_TRUNCATION_RETRIES = 3;
    /** max_tokens 硬上限（自动扩大重试的封顶值） */
    private static final int MAX_TOKENS_HARD_CAP = 65536;

    // ==================== 内置工具定义（S9：plan_task / 文件工具） ====================

    /** 内置任务计划工具名（S9 F5）：本地执行，不走 ToolExecutor 外部链路 */
    private static final String PLAN_TOOL_NAME = "plan_task";
    private static final String PLAN_TOOL_DESCRIPTION =
            "创建或更新当前任务的任务清单。开始多步骤工作前调用一次以制定计划；"
                    + "完成某步骤或计划变化时再次调用以更新整体状态（全量覆盖语义）。"
                    + "提交方案/计划时：以 markdown 参数提供完整计划正文（引擎保存到工作空间 plan/ 目录供用户查看），"
                    + "随后必须调用 ask_user 等待用户批准，批准后方可开始实施。";
    private static final String PLAN_TOOL_SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string","description":"任务清单标题"},"steps":{"type":"array","description":"任务步骤列表（全量提交，以本次调用为准整体覆盖）","items":{"type":"object","properties":{"id":{"type":"string","description":"步骤唯一标识"},"text":{"type":"string","description":"步骤内容"},"status":{"type":"string","enum":["pending","in_progress","done"],"description":"步骤状态"}},"required":["id","text","status"]}},"markdown":{"type":"string","description":"完整计划正文（Markdown）。提交方案/计划时提供，引擎将保存到工作空间 plan/ 目录供用户查看完整计划"}},"required":["steps"]}
            """;

    /** 内置用户问答工具（HITL）：向用户提问并挂起等待回答，回答作为工具结果回灌续跑 */
    private static final String ASK_TOOL_NAME = "ask_user";
    private static final String ASK_TOOL_DESCRIPTION =
            "向用户提问以获取决策确认（执行会挂起，等待用户在界面上选择或输入回答后继续）。"
                    + "适用：方案/计划需要用户批准、存在歧义需要用户决策、缺少关键信息。"
                    + "提交方案/计划（plan_task）后必须调用本工具等待用户批准，批准后方可开始实施。"
                    + "options 提供 1-4 个候选项可降低用户输入成本，用户也可自行输入回答。";
    private static final String ASK_TOOL_SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string","description":"要向用户确认的问题（简洁明确）"},"options":{"type":"array","items":{"type":"string"},"maxItems":4,"description":"候选项（用户可选中或自行输入回答）"}},"required":["question"]}
            """;

    /** 内置文件工具（S9 F3）：经 FileWorkspaceSpi 在会话工作空间执行 */
    private static final String READ_FILE_TOOL = "read_file";
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    private static final String LIST_DIR_TOOL = "list_dir";
    private static final String READ_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径（相对当前工作空间根目录）"}},"required":["path"]}
            """;
    private static final String WRITE_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径"},"content":{"type":"string","description":"完整文件内容（整体覆盖）"}},"required":["path","content"]}
            """;
    private static final String EDIT_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径"},"old_text":{"type":"string","description":"要替换的原文（需唯一匹配）"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["path","old_text","new_text"]}
            """;
    private static final String LIST_DIR_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"目录相对路径，默认工作空间根目录"}},"required":[]}
            """;

    /** 内置子代理派生工具：模型动态派生多个独立 ReAct 会话并行执行，聚合结果回灌主循环 */
    private static final String SPAWN_TOOL_NAME = "spawn_subagents";
    private static final String SPAWN_TOOL_DESCRIPTION =
            "并行派生多个独立子 Agent（各自拥有独立上下文、独立工具循环与预算）分头处理不同子任务，"
                    + "全部执行完成后汇总各子 Agent 结果返回。适用于可并行分解的调研、多方案对比、批量处理等场景。"
                    + "每个子任务的 prompt 必须自包含（子 Agent 看不到当前对话历史）。";
    private static final String SPAWN_TOOL_SCHEMA_TEMPLATE = """
            {"type":"object","properties":{"agents":{"type":"array","description":"子代理任务清单（并行执行）","maxItems":%d,"items":{"type":"object","properties":{"name":{"type":"string","description":"子任务名称（用于结果标注）"},"agentId":{"type":"string","description":"可选，执行该子任务的 Agent 标识，缺省沿用当前 Agent"},"prompt":{"type":"string","description":"子任务的完整自包含提示词（含背景、目标与期望产出）"}},"required":["name","prompt"]}}},"required":["agents"]}
            """;

    /** 超限总结指令（user 角色，追加在对话末尾；总结请求不带 tools，模型无法再调用工具） */
    private static final String SUMMARY_INSTRUCTION =
            "你的任务执行已达到上限（工具调用轮次/执行预算），本次总结将是任务的最终输出，不能再调用任何工具。"
                    + "请基于以上对话中已经获取的全部信息（工具结果与你的分析），用中文输出当前进展的最终总结："
                    + "1) 已完成的工作与关键产出；2) 重要发现或结论；3) 尚未完成的部分与建议的下一步。"
                    + "总结要简洁、结构化、直接给出内容。";
    /** 单次执行的上下文压缩次数上限（防反复压缩） */
    private static final int MAX_CONTEXT_COMPACTS = 5;
    /** 死循环策略提示（nudge，user 角色，追加在工具结果之后、下一轮推理之前） */
    private static final String LOOP_NUDGE_INSTRUCTION =
            "系统提示：你已连续多次以完全相同的参数调用同一工具且任务未推进。"
                    + "请停止重复调用，改用其他方法获取信息，或基于已有信息直接给出最终回答。";

    // ==================== 同步执行 ====================

    @Override
    public LlmResponse execute(AgentTask task) {
        // 输入安全层：提示注入检测（高风险拦截，中风险告警）
        promptInjectionDetector.checkInput(task.getMessage());
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);

        // 感知 -> 复杂度路由 -> 按复杂度预算 -> 模型路由（与流式路径共用决策链）
        ExecutionPlan plan = planExecution(task, pm);
        log.info("同步对话: agentId={}, provider={}, model={}, intentType={}, complexity={}, system={}",
                agentId, plan.provider, plan.model, plan.intent.getIntentType(),
                plan.complexity.getLevel(), plan.complexity.getSystemChoice().getSystem());

        LlmClient client = llmClientRegistry.getClient(plan.provider);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(task, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent, plan.provider, plan.model);
        double temperature = resolveTemperature(task);

        // 语义缓存命中检查：高相似历史请求直接返回缓存响应（零 LLM 成本）
        String cacheContext = agentId != null ? agentId : "default";
        String cached = responseCache.get(task.getMessage(), cacheContext);
        if (cached != null && !cached.isBlank()) {
            log.info("语义缓存命中: agentId={}, responseLength={}", agentId, cached.length());
            recordMetricSafe("agent.cache.hit", 1, Map.of("agentId", agentId != null ? agentId : "default"));
            return LlmResponse.builder().content(cached).build();
        }
        recordMetricSafe("agent.cache.miss", 1, Map.of("agentId", agentId != null ? agentId : "default"));

        log.info("调用 LLM(同步): provider={}, model={}, messages={}, tools={}, temperature={}",
                plan.provider, plan.model, messages.size(), tools.size(), temperature);

        // 推理模型思考耗尽预算的自动重试状态（S8：finish=length 且正文为空时加倍 max_tokens）
        int currentMaxTokens = clampToModelOutputWindow(plan.model, resolveMaxTokens(task));
        int truncationRetries = 0;

        // 死循环检测器（优化3）：同步路径同样生效
        ToolLoopDetector loopDetector = new ToolLoopDetector();
        // 轮次闸门等级感知：上限取预算账本的 maxRounds（随 L1/L2/L3 等级与截断重试扩容变化），
        // 而非固定的 config.maxToolRounds——否则 L3 的 20 轮预算永远先被 10 轮执行器闸门卡住
        for (int round = 0; round < plan.budget.getMaxRounds(); round++) {
            // 时间预算滚动续期（S9 方案A）：时间满额但 Token/轮次健康 → 续期不终止
            budgetController.renewTimeBudget(plan.budget);
            // 预算检查：熔断则终止（仅 Token/轮次，配置金额上限后含成本维）
            if (budgetController.shouldStop(plan.budget)) {
                log.warn("预算熔断: tokenUtil={}, timeUtil={}, costUtil={}, round={}",
                        plan.budget.getTokenUtilization(), plan.budget.getTimeUtilization(),
                        plan.budget.getCostUtilization(), round);
                recordMetricSafe("agent.budget.exceeded", 1, Map.of(
                        "agentId", agentId != null ? agentId : "unknown", "reason", "sync_loop"));
                throw AgentEngineException.of("BUDGET_EXCEEDED",
                        budgetExceededMessage(plan.budget)
                                + ", time=" + plan.budget.getElapsedMs() + "ms/" + plan.budget.getTimeBudgetMs() + "ms");
            }

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(plan.model)
                    .messages(messages)
                    .tools(tools.isEmpty() ? null : tools)
                    .temperature(temperature)
                    .maxTokens(currentMaxTokens)
                    .stream(false)
                    .build();

            // OTel 追踪：每轮 LLM 调用包装 Span（TraceService SPI 桥接）
            Object llmSpan = traceService.startSpan(task.getSessionId(), task.getAgentId(), "llm_call");
            LlmResponse response;
            try {
                response = client.chat(llmRequest);
                traceService.endSpan(llmSpan);
            } catch (RuntimeException e) {
                traceService.endSpanWithError(llmSpan, e);
                throw e;
            }

            // 记录预算消耗（优化2：成本按模型定价对输入/输出拆分计价；
            // usage 缺失拆分时全部计入输出侧——保守高估，利于金额熔断）
            LlmResponse.Usage usage = response.getUsage();
            long promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            long tokens;
            long completionTokens;
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                tokens = usage.getTotalTokens();
                completionTokens = usage.getCompletionTokens() != null
                        ? usage.getCompletionTokens() : Math.max(0, tokens - promptTokens);
            } else {
                tokens = promptTokens;
                completionTokens = 0;
            }
            budgetController.consume(plan.budget, tokens,
                    budgetController.calculateCost(plan.model, promptTokens, completionTokens));

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                boolean truncated = "length".equals(response.getFinishReason());
                boolean blankContent = response.getContent() == null || response.getContent().isBlank();
                // 推理模型截断自愈（S8/S9）：finish=length 时自动加倍 max_tokens 重试
                // （上限 65536）。S9 起覆盖「正文非空中途截断」——丢弃部分内容整体
                // 重新生成，而非静默返回半截回复。预算熔断（shouldStop）是重试的自然上界。
                if (truncated
                        && truncationRetries < MAX_TRUNCATION_RETRIES
                        && currentMaxTokens < MAX_TOKENS_HARD_CAP) {
                    truncationRetries++;
                    currentMaxTokens = clampToModelOutputWindow(plan.model,
                            Math.min(currentMaxTokens * 2, MAX_TOKENS_HARD_CAP));
                    // 主动升级预算：token/时间预算随 max_tokens 同步放大，否则重试轮次
                    // 会在循环头的 shouldStop 处被熔断（尤其 L1 级仅 30s 时间预算）。
                    // consume() 会累计 currentRound，重试也占轮次，需同步扩容 maxRounds
                    plan.budget.setTokenBudget(plan.budget.getTokenBudget() * 2);
                    plan.budget.setTimeBudgetMs(Math.max(plan.budget.getTimeBudgetMs() * 2,
                            plan.budget.getElapsedMs() * 3));
                    plan.budget.setMaxRounds(plan.budget.getMaxRounds() + 1);
                    log.warn("推理模型回复被截断（finish=length，正文{}），自动扩大 max_tokens 至 {} 重试（第 {}/{} 次）",
                            blankContent ? "为空" : "不完整", currentMaxTokens, truncationRetries, MAX_TRUNCATION_RETRIES);
                    continue;
                }
                if (truncated && blankContent) {
                    // 重试预算耗尽且无任何正文：明确报错，不再返回空回复
                    throw AgentEngineException.of("TRUNCATED",
                            "AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                    "请增大 max_tokens 或简化问题后重试。");
                }
                if (truncated) {
                    // 重试耗尽但已有部分正文：保留部分内容并明示不完整（响应带 finishReason=length）
                    log.warn("截断重试预算耗尽，保留部分回复（finishReason=length）");
                }
                // 输出安全层：PII 脱敏后再持久化/缓存/返回
                response.setContent(outputSanitizer.checkOutput(response.getContent()));
                if (!truncated) {
                    storeExperience(task, response.getContent());
                    // 写入语义缓存（供后续相似请求命中）——截断的部分内容不入缓存
                    responseCache.put(task.getMessage(), response.getContent(), cacheContext);
                }
                recordSuccess(task, plan.budget);
                return response;
            }

            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .toolCalls(response.getToolCalls())
                    .build());

            // 死循环检测（优化3）：本轮工具调用签名入账——达终止阈值在执行前直接抛出
            // （节省工具执行成本），达告警阈值在工具结果落盘后注入策略提示
            int loopMax = 0;
            boolean loopWarn = false;
            if (config.isLoopDetectionEnabled()) {
                for (ToolCall tc : response.getToolCalls()) {
                    int c = loopDetector.record(tc.getName(), tc.getArguments());
                    if (c > loopMax) {
                        loopMax = c;
                    }
                }
                if (loopMax >= config.getLoopStopThreshold()) {
                    log.warn("检测到工具调用死循环(同步): 连续相同调用 {} 次, round={}", loopMax, round);
                    recordMetricSafe("agent.loop.detected", 1, Map.of(
                            "agentId", agentId != null ? agentId : "unknown",
                            "consecutive", String.valueOf(loopMax)));
                    throw AgentEngineException.of("LOOP_DETECTED",
                            "检测到重复工具调用循环（相同工具与参数已连续调用 " + loopMax + " 次），已强制终止");
                }
                loopWarn = loopMax >= config.getLoopWarnThreshold() && !loopDetector.isNudged();
                if (loopWarn) {
                    loopDetector.markNudged();
                }
            }

            List<CompletableFuture<ToolResult>> futures = response.getToolCalls().stream()
                    .map(tc -> CompletableFuture.supplyAsync(() -> {
                        // OTel 追踪：每次工具执行包装 Span
                        Object toolSpan = traceService.startSpan(task.getSessionId(), tc.getName(), "tool_call");
                    try {
                        ToolResult r = isBuiltinTool(tc.getName())
                                ? executeBuiltinToolSync(tc, toolContext, plan.budget)
                                : toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                        toolConfigMap.get(tc.getName()));
                        traceService.endSpan(toolSpan);
                        return r;
                    } catch (RuntimeException e) {
                            traceService.endSpanWithError(toolSpan, e);
                            throw e;
                        }
                    }, config.getToolExecutor()))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (int i = 0; i < response.getToolCalls().size(); i++) {
                ToolCall toolCall = response.getToolCalls().get(i);
                ToolResult result = futures.get(i).join();
                String output = result.isSuccess() ? result.getOutput() : result.getError();
                messages.add(Message.builder()
                        .role("tool")
                        .content(output)
                        .toolCallId(toolCall.getId())
                        .name(toolCall.getName())
                        .build());
            }

            // 死循环告警（优化3）：策略提示在工具结果落盘后、下一轮推理前注入
            if (loopWarn) {
                messages.add(Message.builder().role("user").content(LOOP_NUDGE_INSTRUCTION).build());
                log.info("死循环策略提示已注入(同步): 连续相同调用 {} 次, round={}", loopMax, round);
            }
        }

        throw AgentEngineException.of("TOOL_ROUNDS_EXCEEDED", "工具调用轮次超限");
    }

    // ==================== 流式执行 ====================

    @Override
    public Flux<AgentEvent> executeStream(AgentTask task) {
        // 输入安全层：提示注入检测（高风险拦截，中风险告警）
        promptInjectionDetector.checkInput(task.getMessage());
        String agentId = task.getAgentId();
        AgentSpec agent = loadAgent(agentId);
        String[] pm = resolveProviderAndModel(agent, task);

        // 感知 -> 复杂度路由 -> 按复杂度预算 -> 模型路由（与同步路径共用决策链）
        ExecutionPlan plan = planExecution(task, pm);
        log.info("流式对话: agentId={}, provider={}, model={}, intentType={}, complexity={}, system={}",
                agentId, plan.provider, plan.model, plan.intent.getIntentType(),
                plan.complexity.getLevel(), plan.complexity.getSystemChoice().getSystem());

        LlmClient client = llmClientRegistry.getClient(plan.provider);
        List<Message> messages = buildMessages(agent, task);
        Map<String, ToolConfig> toolConfigMap = new LinkedHashMap<>();
        List<ToolDefinition> tools = buildToolDefinitions(task, toolConfigMap);
        ToolContext toolContext = buildToolContext(task, agent, plan.provider, plan.model);
        double temperature = resolveTemperature(task);

        // 语义缓存命中检查：直接以 CONTENT+DONE 事件回放缓存响应（零 LLM 成本）
        String cacheContext = agentId != null ? agentId : "default";
        String cached = responseCache.get(task.getMessage(), cacheContext);
        if (cached != null && !cached.isBlank()) {
            log.info("语义缓存命中(流式): agentId={}, responseLength={}", agentId, cached.length());
            recordMetricSafe("agent.cache.hit", 1, Map.of("agentId", agentId != null ? agentId : "default"));
            return Flux.just(AgentEvent.builder().type(AgentEvent.CONTENT).content(cached).build(),
                    AgentEvent.builder().type(AgentEvent.DONE).build());
        }
        recordMetricSafe("agent.cache.miss", 1, Map.of("agentId", agentId != null ? agentId : "default"));

        log.info("调用 LLM(流式): provider={}, model={}, messages={}, tools={}, temperature={}",
                plan.provider, plan.model, messages.size(), tools.size(), temperature);

        StringBuilder contentTracker = new StringBuilder();
        int maxTokens = clampToModelOutputWindow(plan.model, resolveMaxTokens(task));
        // 账本准确性（S9 方案A）：预算熔断的运行不算成功，且部分内容不沉淀经验
        java.util.concurrent.atomic.AtomicBoolean budgetStopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 轮次超限同样不算成功（用户实报）：带内 ERROR+DONE(rounds) 终止，部分进度
        // 不沉淀经验——与预算熔断语义对齐，此前误走 recordSuccess/storeExperience
        java.util.concurrent.atomic.AtomicBoolean roundsStopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 死循环检测器（优化3）：单次执行内跨轮次记录工具调用签名
        ToolLoopDetector loopDetector = new ToolLoopDetector();
        return Flux.defer(() -> streamRound(client, plan.model, messages, tools, toolConfigMap, toolContext, temperature, maxTokens, 0, 0, plan.budget, loopDetector))
                .doOnNext(event -> {
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        contentTracker.append(event.getContent());
                    }
                    if (AgentEvent.BUDGET_EXCEEDED.equals(event.getType())) {
                        budgetStopped.set(true);
                    }
                    if (AgentEvent.ERROR.equals(event.getType()) && event.getMetadata() != null
                            && "tool_rounds_exceeded".equals(event.getMetadata().get("reason"))) {
                        roundsStopped.set(true);
                    }
                    // 优雅降级路径（总结成功）没有 ERROR 事件，靠 DONE 的 finishReason 识别
                    if (AgentEvent.DONE.equals(event.getType()) && event.getFinishReason() != null
                            && ("rounds".equals(event.getFinishReason()) || "loop".equals(event.getFinishReason()))) {
                        roundsStopped.set(true);
                    }
                    if (AgentEvent.DONE.equals(event.getType()) && "budget".equals(event.getFinishReason())) {
                        budgetStopped.set(true);
                    }
                })
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        if (budgetStopped.get() || roundsStopped.get()) {
                            // 熔断/轮次超限：记失败账本，部分内容不沉淀经验（避免不完整回复污染记忆）
                            recordFailure(task, budgetStopped.get()
                                    ? "BUDGET_EXCEEDED: 预算耗尽，已保留部分进度"
                                    : "TOOL_ROUNDS_EXCEEDED: 工具调用轮次超限");
                            return;
                        }
                        // 输出安全层：PII 脱敏后再持久化经验
                        storeExperience(task, outputSanitizer.checkOutput(contentTracker.toString()));
                        recordSuccess(task, plan.budget);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Agent 流式执行异常: agentId={}", task.getAgentId(), e);
                    String reason = e.getMessage() != null ? e.getMessage() : "stream_error";
                    recordFailure(task, reason);
                    return Flux.just(
                            AgentEvent.builder()
                                    .type(AgentEvent.FAILURE_RECORDED)
                                    .content("失败已记录：" + reason)
                                    .build(),
                            AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage(e.getMessage() != null ? e.getMessage() : "AI 处理失败")
                                    .build());
                });
    }

    private Flux<AgentEvent> streamRound(LlmClient client, String model, List<Message> messages,
                                         List<ToolDefinition> tools, Map<String, ToolConfig> toolConfigMap,
                                         ToolContext toolContext, double temperature, int maxTokens, int round,
                                         int truncationRetries, BudgetContext budget, ToolLoopDetector loopDetector) {
        // 轮次闸门等级感知（与同步路径同源）：上限取预算账本 maxRounds，L3 的 20 轮预算
        // 不再被固定 10 轮闸门提前卡住。超限优雅降级（优化1）：先尝试无 tools 的总结调用
        // 把"任务失败"变为"降级完成"（finishReason=rounds），总结失败回退 ERROR 终止。
        // 轮次滚动扩容：到顶但无死循环迹象时扩 50% 续跑（与时间滚动续期同哲学），
        // 扩容次数受 roundsRenewMax 约束，耗尽后仍走强制总结（绝对上限防失控）
        if (round >= budget.getMaxRounds()) {
            if (config.isRoundsRenewEnabled()
                    && budget.getRoundsRenewed() < config.getRoundsRenewMax()
                    && loopDetector.consecutiveCount() < config.getLoopWarnThreshold()) {
                budgetController.renewRounds(budget);
                log.info("轮次滚动扩容(流式): round={}, newMaxRounds={}, renewals={}/{}",
                        round, budget.getMaxRounds(), budget.getRoundsRenewed(), config.getRoundsRenewMax());
                return Flux.concat(
                        Flux.just(AgentEvent.builder()
                                .type(AgentEvent.BUDGET_WARNING)
                                .content(String.format("已达轮次上限 %d 轮，检测到任务仍在推进，已自动扩容至 %d 轮继续执行（第 %d/%d 次扩容）",
                                        round, budget.getMaxRounds(), budget.getRoundsRenewed(),
                                        config.getRoundsRenewMax()))
                                .build()),
                        streamRound(client, model, messages, tools, toolConfigMap, toolContext,
                                temperature, maxTokens, round, truncationRetries, budget, loopDetector));
            }
            return summaryOnLimit(client, model, messages, temperature, maxTokens, budget,
                    "已达工具调用轮次上限（" + budget.getMaxRounds() + " 轮），正在总结当前进度...",
                    "rounds",
                    () -> Flux.just(AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage("工具调用轮次超限")
                                    .metadata(java.util.Map.of("reason", "tool_rounds_exceeded"))
                                    .build(),
                            AgentEvent.builder().type(AgentEvent.DONE).finishReason("rounds").build()));
        }

        // 时间预算滚动续期（S9 方案A）：时间满额但 Token/轮次健康 → 续期并发告警事件，
        // 不终止——持续健康推进的长任务（马拉松思考/多轮工具）不再被墙钟误杀
        java.util.List<AgentEvent> budgetHeadEvents = new ArrayList<>();
        if (budget.getTimeUtilization() >= 1.0 && budgetController.renewTimeBudget(budget)) {
            budgetHeadEvents.add(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_WARNING)
                    .content(String.format("执行时间较长，已自动续期时间预算（累计 %d 秒），任务继续推进中",
                            budget.getElapsedMs() / 1000))
                    .build());
        }

        // 预算熔断检查（仅 Token 超限 / 轮次超限阻断，配置金额上限后含成本维）
        if (budgetController.shouldStop(budget)) {
            log.warn("预算熔断(流式): tokenUtil={}, timeUtil={}, costUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(), budget.getCostUtilization(), round);
            recordMetricSafe("agent.budget.exceeded", 1, Map.of(
                    "agentId", toolContext.getAgentId() != null ? toolContext.getAgentId() : "unknown",
                    "reason", "stream_loop"));
            // 熔断后补发 done（finishReason=budget）保证前端生命周期完整：
            // 此前流静默结束且 budget_exceeded 被前端忽略，表现为「无报错无结果地断开」
            AgentEvent exceededEvent = AgentEvent.builder()
                    .type(AgentEvent.BUDGET_EXCEEDED)
                    .errorMessage(budgetExceededMessage(budget))
                    .metadata(java.util.Map.of("tokenConsumed", budget.getTokenConsumed(),
                            "tokenBudget", budget.getTokenBudget(),
                            "elapsedMs", budget.getElapsedMs()))
                    .build();
            java.util.List<AgentEvent> stopHead = new ArrayList<>(budgetHeadEvents);
            stopHead.add(exceededEvent);
            // 熔断优雅降级（优化1）：先发 stopHead（续期告警 + BUDGET_EXCEEDED，前端 banner
            // 照常），再接总结收尾；总结失败回退为仅补 DONE(budget) 的原终止序列
            return Flux.concat(Flux.fromIterable(stopHead),
                    summaryOnLimit(client, model, messages, temperature, maxTokens, budget,
                            "任务预算已用尽，正在总结当前进度...",
                            "budget",
                            () -> Flux.just(AgentEvent.builder().type(AgentEvent.DONE).finishReason("budget").build())));
        }

        // 预算告警（阈值可配，budget_warning 事件对用户可见）。
        // 告警去重：仅在级别跃迁时发送（NORMAL→ALERT→DEGRADE），同级静默——
        // 时间续期后利用率重置 ≈67%，若不去重则每轮都会重新越过 70% 刷屏（用户实报）
        BudgetStatus status = budgetController.check(budget);
        int alertLevel = status == BudgetStatus.DEGRADE ? 2 : status == BudgetStatus.ALERT ? 1 : 0;
        if (alertLevel > budget.getLastAlertLevel()) {
            budget.setLastAlertLevel(alertLevel);
            log.info("预算告警(流式): tokenUtil={}, timeUtil={}, round={}",
                    budget.getTokenUtilization(), budget.getTimeUtilization(), round);
            // 文案按预算维度如实区分：token 不限量（未绑定套餐哨兵）时只说时间，
            // 避免"使用率 87%（token 0%）"这类自相矛盾的读数造成误解
            String content = budget.isTokenUnlimited()
                    ? String.format("任务执行时间较长：已用时 %d 分钟，时间预算滚动续期中，任务正常推进",
                            budget.getElapsedMs() / 60_000)
                    : String.format("任务预算使用率已达 %d%%（token %d%% · 时间 %d%%）",
                            (int) Math.round(Math.max(budget.getTokenUtilization(), budget.getTimeUtilization()) * 100),
                            (int) Math.round(budget.getTokenUtilization() * 100),
                            (int) Math.round(budget.getTimeUtilization() * 100));
            budgetHeadEvents.add(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_WARNING)
                    .content(content)
                    .build());
        }

        LlmRequest llmRequest = LlmRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools.isEmpty() ? null : tools)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(true)
                .build();

        StringBuilder contentBuilder = new StringBuilder();
        Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();
        String[] finishReasonHolder = {null};
        long[] reasoningChars = {0};

        // OTel 追踪：流式 LLM 调用包装 Span（订阅时开启，流终止时结束）
        Flux<AgentEvent> roundFlux = Flux.defer(() -> {
                    Object llmSpan = traceService.startSpan(toolContext.getSessionId(), toolContext.getAgentId(), "llm_call_stream");
                    return client.chatStream(llmRequest)
                            .doFinally(signal -> {
                                if (signal == reactor.core.publisher.SignalType.ON_ERROR) {
                                    traceService.endSpanWithError(llmSpan, null);
                                } else {
                                    traceService.endSpan(llmSpan);
                                }
                            });
                })
                .flatMapIterable(chunk -> {
                    // 先记录 finish_reason 再处理增量：部分供应商把 finish_reason 放在
                    // 最后一个 content/reasoning chunk 上，若先 return 会丢失截断信号
                    if (chunk.getFinishReason() != null) {
                        finishReasonHolder[0] = chunk.getFinishReason();
                    }
                    List<AgentEvent> events = new ArrayList<>(2);
                    if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty()) {
                        reasoningChars[0] += chunk.getReasoning().length();
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.THINKING)
                                .reasoning(chunk.getReasoning())
                                .build());
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        contentBuilder.append(chunk.getDelta());
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.CONTENT)
                                .content(chunk.getDelta())
                                .build());
                    }
                    if (chunk.getToolCallDelta() != null) {
                        accumulateToolCall(toolCallAccumulators, chunk.getToolCallDelta());
                    }
                    return events;
                })
                .filter(event -> event.getType() != null)
                .startWith(AgentEvent.builder()
                        .type(AgentEvent.STATUS)
                        .content(round == 0 ? "正在思考..." : "正在继续推理...")
                        .build())
                .concatWith(Flux.defer(() -> {
                    String content = contentBuilder.toString();
                    List<ToolCall> toolCalls = assembleToolCalls(toolCallAccumulators);

                    // 流式 Token 记账（S9 方案A）：流式路径此前从不 consume，Token 预算
                    // 形同虚设（tokenUtil 恒 0）；按字符量估算本轮消耗（CJK 约 3 字符/token），
                    // 并拆分输入（历史消息）与输出（本轮生成）两段分别计价（优化2）
                    {
                        long outChars = reasoningChars[0] + content.length();
                        long inChars = 0;
                        for (Message m : messages) {
                            inChars += m.getContent() != null ? m.getContent().length() : 0;
                        }
                        long inEst = Math.max(1, inChars / 3);
                        long outEst = Math.max(1, outChars / 3);
                        budgetController.consume(budget, inEst + outEst,
                                budgetController.calculateCost(model, inEst, outEst));
                    }

                    messages.add(Message.builder()
                            .role("assistant")
                            .content(content)
                            .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                            .build());

                    if (toolCalls.isEmpty()) {
                        boolean truncated = "length".equals(finishReasonHolder[0]);
                        boolean blankContent = content.isBlank();
                        // 推理模型截断自愈（S8/S9）：finish=length 时自动加倍 max_tokens 重试。
                        // S9 起覆盖「正文非空中途截断」：先发 CONTENT_RESET 通知前端清空已
                        // 流出的部分正文，再整体重新生成，而非静默结束在半截回复上。
                        if (truncated
                                && truncationRetries < MAX_TRUNCATION_RETRIES
                                && maxTokens < MAX_TOKENS_HARD_CAP) {
                            int nextMaxTokens = clampToModelOutputWindow(model,
                                    Math.min(maxTokens * 2, MAX_TOKENS_HARD_CAP));
                            log.warn("推理模型回复被截断（finish=length，正文{}），自动扩大 max_tokens 至 {} 重试（第 {}/{} 次）: model={}",
                                    blankContent ? "为空" : "不完整",
                                    nextMaxTokens, truncationRetries + 1, MAX_TRUNCATION_RETRIES, model);
                            // 移除本轮的空/半截 assistant 消息避免污染上下文
                            if (!messages.isEmpty()) {
                                messages.remove(messages.size() - 1);
                            }
                            // 主动升级预算：重试轮次的 token/时间预算随 max_tokens 同步放大，
                            // 否则递归入口的 shouldStop 会因时间预算（L1 仅 30s）直接熔断。
                            // consume() 累计 currentRound，重试也占轮次，需同步扩容 maxRounds
                            budget.setTokenBudget(budget.getTokenBudget() * 2);
                            budget.setTimeBudgetMs(Math.max(budget.getTimeBudgetMs() * 2,
                                    budget.getElapsedMs() * 3));
                            budget.setMaxRounds(budget.getMaxRounds() + 1);
                            // 正文已部分流出时，通知调用方清空累积内容（旧内容将被重新生成替换）
                            AgentEvent resetEvent = blankContent ? null : AgentEvent.builder()
                                    .type(AgentEvent.CONTENT_RESET)
                                    .content("回复被截断，正在重新生成")
                                    .build();
                            AgentEvent statusEvent = AgentEvent.builder()
                                    .type(AgentEvent.STATUS)
                                    .content("回复超限，正在扩大预算重新生成...")
                                    .build();
                            Flux<AgentEvent> retryHead = resetEvent != null
                                    ? Flux.just(resetEvent, statusEvent)
                                    : Flux.just(statusEvent);
                            return retryHead.concatWith(Flux.defer(() ->
                                    streamRound(client, model, messages, tools, toolConfigMap, toolContext,
                                            temperature, nextMaxTokens, round, truncationRetries + 1, budget, loopDetector)));
                        }
                        if (truncated && blankContent) {
                            log.warn("LLM 回复被截断且重试预算耗尽: finish_reason=length, model={}", model);
                            return Flux.just(AgentEvent.builder()
                                    .type(AgentEvent.ERROR)
                                    .errorMessage("AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。" +
                                            "请增大 max_tokens 或简化问题后重试。")
                                    .build());
                        }
                        if (truncated) {
                            // 重试耗尽但已有部分正文：保留内容，STATUS 明示不完整，done 带 finishReason=length
                            log.warn("截断重试预算耗尽，保留部分回复: model={}", model);
                            return Flux.just(
                                    AgentEvent.builder().type(AgentEvent.STATUS)
                                            .content("回复因 token 上限被截断，可能不完整")
                                            .build(),
                                    AgentEvent.builder().type(AgentEvent.EXPERIENCE_SAVED)
                                            .content("执行经验已沉淀至长期记忆")
                                            .build(),
                                    doneEvent(toolContext, finishReasonHolder[0], budget));
                        }
                        // 经验沉淀通知 + 完成事件（T4.5：实际写入在流终止回调，
                        // 此事件告知前端本次交互将沉淀为长期记忆）
                        return Flux.just(
                                AgentEvent.builder().type(AgentEvent.EXPERIENCE_SAVED)
                                        .content("执行经验已沉淀至长期记忆")
                                        .build(),
                                doneEvent(toolContext, finishReasonHolder[0], budget));
                    }

                    // 死循环检测（优化3）：toolCalls 组装后、执行前记录签名——达到终止阈值
                    // 直接收尾（不执行本轮工具，节省执行成本），达到告警阈值注入策略提示
                    // （一次性赋值保持 effectively-final，供尾部 lambda 捕获）
                    final int loopMax;
                    final boolean loopStop;
                    final boolean loopWarn;
                    if (config.isLoopDetectionEnabled()) {
                        int max = 0;
                        for (ToolCall tc : toolCalls) {
                            int c = loopDetector.record(tc.getName(), tc.getArguments());
                            if (c > max) {
                                max = c;
                            }
                        }
                        loopMax = max;
                        loopStop = loopMax >= config.getLoopStopThreshold();
                        boolean warnCandidate = !loopStop && loopMax >= config.getLoopWarnThreshold();
                        if (warnCandidate && !loopDetector.isNudged()) {
                            loopDetector.markNudged();
                            loopWarn = true;
                        } else {
                            loopWarn = false;
                        }
                    } else {
                        loopMax = 0;
                        loopStop = false;
                        loopWarn = false;
                    }
                    if (loopStop) {
                        log.warn("检测到工具调用死循环(流式): 连续相同调用 {} 次, round={}", loopMax, round);
                        recordMetricSafe("agent.loop.detected", 1, Map.of(
                                "agentId", toolContext.getAgentId() != null ? toolContext.getAgentId() : "unknown",
                                "consecutive", String.valueOf(loopMax)));
                        return summaryOnLimit(client, model, messages, temperature, maxTokens, budget,
                                "检测到重复执行循环（相同工具与参数已连续调用 " + loopMax + " 次），已自动停止并总结进度",
                                "loop",
                                () -> Flux.just(AgentEvent.builder()
                                                .type(AgentEvent.ERROR)
                                                .errorMessage("检测到重复工具调用循环（连续 " + loopMax + " 次相同调用）")
                                                .metadata(java.util.Map.of("reason", "tool_loop_detected"))
                                                .build(),
                                        AgentEvent.builder().type(AgentEvent.DONE).finishReason("loop").build()));
                    }

                    List<AgentEvent> toolCallEvents = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        toolCallEvents.add(AgentEvent.builder()
                                .type(AgentEvent.TOOL_CALL)
                                .toolCall(AgentEvent.ToolCallInfo.builder()
                                        .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                        .build())
                                .build());
                    }
                    List<AgentEvent> executingEvents = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        executingEvents.add(AgentEvent.builder()
                                .type(AgentEvent.TOOL_EXECUTING)
                                .toolCall(AgentEvent.ToolCallInfo.builder()
                                        .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                        .build())
                                .build());
                    }

                    return Flux.fromIterable(toolCallEvents)
                            .concatWith(Flux.fromIterable(executingEvents))
                            .concatWith(Flux.fromIterable(toolCalls)
                                    .flatMap(tc -> {
                                        // 内置工具（S9）：plan_task（F5）、spawn_subagents、文件工具（F3）
                                        // 本地/SPI 执行，不走 ToolExecutor 外部链路
                                        if (isBuiltinTool(tc.getName())) {
                                            return executeBuiltinToolStream(tc, toolContext, budget, messages);
                                        }
                                        return Mono.fromFuture(CompletableFuture.supplyAsync(
                                                        () -> toolExecutor.execute(tc.getName(), tc.getArguments(), toolContext,
                                                                toolConfigMap.get(tc.getName())),
                                                        config.getToolExecutor()))
                                                .map(result -> {
                                                    String output = result.isSuccess() ? result.getOutput() : result.getError();
                                                    appendToolMessage(messages, tc, output);
                                                    return toolResultEvent(tc, output);
                                                });
                                    })
                                    .concatWith(Flux.defer(() -> {
                                        // 工具结果落盘后、下一轮推理前：死循环告警注入 + 上下文压缩检查
                                        List<AgentEvent> head = new ArrayList<>();
                                        if (loopWarn) {
                                            synchronized (messages) {
                                                messages.add(Message.builder().role("user")
                                                        .content(LOOP_NUDGE_INSTRUCTION).build());
                                            }
                                            head.add(AgentEvent.builder()
                                                    .type(AgentEvent.BUDGET_WARNING)
                                                    .content("检测到重复的工具调用（第 " + loopMax + " 次相同调用），已提示模型改变策略")
                                                    .build());
                                        }
                                        head.addAll(maybeCompactContext(model, messages, budget));
                                        return Flux.concat(Flux.fromIterable(head),
                                                streamRound(client, model, messages, tools, toolConfigMap,
                                                        toolContext, temperature, maxTokens, round + 1,
                                                        truncationRetries, budget, loopDetector));
                                    })));
                }));
        // 轮次头产生的预算事件（续期/告警/熔断）前置到本轮流输出
        return budgetHeadEvents.isEmpty()
                ? roundFlux
                : Flux.concat(Flux.fromIterable(budgetHeadEvents), roundFlux);
    }

    /**
     * 超限优雅降级（优化1，借鉴 OpenCode 强制纯文本 / CrewAI、smolagents 强制最佳答案）：
     * 达到轮次/预算/循环终止上限时，发一次不带 tools 的流式总结调用，强制模型基于
     * 已有工具结果给出"尽力总结"——把裸报错变为降级完成。
     * <p>头部发 BUDGET_WARNING（toast 可见）+ STATUS（时间线状态行）；总结完成后发
     * DONE(finishReason)；总结调用失败时回退到 fallbackSupplier 的兜底终止序列。
     * 总结是有意的有界开销（无 tools、maxTokens 取配置
     * {@code agent.engine.engine.limit-summary-max-tokens}，默认 8192、硬上限 65536，
     * 与当轮任务级 maxTokens 相互独立）。
     */
    private Flux<AgentEvent> summaryOnLimit(LlmClient client, String model, List<Message> messages,
                                            double temperature, int maxTokens, BudgetContext budget,
                                            String notice, String finishReason,
                                            java.util.function.Supplier<Flux<AgentEvent>> fallbackSupplier) {
        if (!config.isLimitSummaryEnabled()) {
            return fallbackSupplier.get();
        }
        // 复制消息列表追加指令，不污染跨轮共享的 messages（总结后执行即结束，防御性隔离）
        List<Message> summaryMessages = new ArrayList<>(messages);
        summaryMessages.add(Message.builder().role("user").content(SUMMARY_INSTRUCTION).build());
        LlmRequest summaryRequest = LlmRequest.builder()
                .model(model)
                .messages(summaryMessages)
                .temperature(temperature)
                .maxTokens(Math.min(config.getLimitSummaryMaxTokens(), MAX_TOKENS_HARD_CAP))
                .stream(true)
                .build();
        StringBuilder summaryContent = new StringBuilder();
        StringBuilder summaryReasoning = new StringBuilder();
        Flux<AgentEvent> summaryFlux = Flux.defer(() -> client.chatStream(summaryRequest))
                .flatMapIterable(chunk -> {
                    List<AgentEvent> events = new ArrayList<>(2);
                    if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty()) {
                        summaryReasoning.append(chunk.getReasoning());
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.THINKING)
                                .reasoning(chunk.getReasoning())
                                .build());
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        summaryContent.append(chunk.getDelta());
                        events.add(AgentEvent.builder()
                                .type(AgentEvent.CONTENT)
                                .content(chunk.getDelta())
                                .build());
                    }
                    return events;
                })
                .filter(event -> event.getType() != null)
                .concatWith(Flux.defer(() -> {
                    // 总结调用的 token 记账（超限后的收尾开销，如实入账）
                    long estimated = Math.max(1,
                            (summaryReasoning.length() + summaryContent.length()) / 3);
                    budgetController.consume(budget, estimated,
                            budgetController.calculateCost(model, estimated / 2, estimated - estimated / 2));
                    return Flux.just(AgentEvent.builder()
                            .type(AgentEvent.DONE)
                            .finishReason(finishReason)
                            .build());
                }))
                .onErrorResume(e -> {
                    log.warn("超限总结生成失败，回退终止事件序列: finishReason={}, cause={}",
                            finishReason, e.getMessage());
                    return fallbackSupplier.get();
                });
        List<AgentEvent> head = List.of(
                AgentEvent.builder().type(AgentEvent.BUDGET_WARNING).content(notice).build(),
                AgentEvent.builder().type(AgentEvent.STATUS).content("正在总结当前进度...").build());
        return Flux.concat(Flux.fromIterable(head), summaryFlux);
    }

    /**
     * 上下文压缩检查（配额与上下文自治，取代单任务 token 熔断的续跑机制）：
     * 估算输入 tokens 达到模型上下文窗口阈值（context_window_input × 阈值）时，
     * 经 ContextCompactor SPI 压缩历史（保留系统提示 + 最近 N 轮 + 旧内容摘要），
     * 压缩后任务继续。每执行最多 MAX_CONTEXT_COMPACTS 次；压缩无效放行。
     */
    private List<AgentEvent> maybeCompactContext(String model, List<Message> messages, BudgetContext budget) {
        Integer window = modelContextProvider != null ? modelContextProvider.contextWindowInput(model) : null;
        if (window == null || window <= 0 || config.getContextCompactThreshold() <= 0) {
            return List.of();
        }
        if (budget.getContextCompactions() >= MAX_CONTEXT_COMPACTS) {
            return List.of();
        }
        long inTokens = estimateContextTokens(messages);
        if (inTokens < window * config.getContextCompactThreshold()) {
            return List.of();
        }
        if (contextCompactor == null) {
            log.warn("上下文接近模型窗口且未配置压缩器: model={}, inTokens~{}, window={}", model, inTokens, window);
            return List.of(AgentEvent.builder()
                    .type(AgentEvent.BUDGET_WARNING)
                    .content(String.format("上下文已接近模型窗口（约 %d%%），且未配置压缩策略", inTokens * 100 / window))
                    .build());
        }
        int before = messages.size();
        List<Message> compacted = contextCompactor.compact(messages, config.getContextCompactKeepRounds());
        if (compacted == null || compacted.isEmpty() || compacted.size() >= before) {
            log.warn("上下文压缩无效（跳过）: before={}, after={}", before, compacted == null ? -1 : compacted.size());
            return List.of();
        }
        messages.clear();
        messages.addAll(compacted);
        budget.setContextCompactions(budget.getContextCompactions() + 1);
        log.info("上下文压缩完成: model={}, {}条→{}条, 第 {}/{} 次", model, before, messages.size(),
                budget.getContextCompactions(), MAX_CONTEXT_COMPACTS);
        return List.of(AgentEvent.builder()
                .type(AgentEvent.STATUS)
                .content(String.format("上下文接近模型窗口（约 %d%%），已压缩历史（%d→%d 条），任务继续推进中",
                        inTokens * 100 / window, before, messages.size()))
                .build());
    }

    /** 上下文 tokens 估算（与流式记账同口径：字符数 ÷ 3）。 */
    private long estimateContextTokens(List<Message> messages) {
        long chars = 0;
        for (Message m : messages) {
            chars += m.getContent() != null ? m.getContent().length() : 0;
        }
        return Math.max(1, chars / 3);
    }

    /** 输出上限校验：max_tokens 与模型 context_window_output 取小（未知则不限制）。 */
    private int clampToModelOutputWindow(String model, int maxTokens) {
        if (modelContextProvider == null) {
            return maxTokens;
        }
        Integer out = modelContextProvider.contextWindowOutput(model);
        return (out != null && out > 0) ? Math.min(maxTokens, out) : maxTokens;
    }

    /** 熔断原因文案：token 维 + 配置了金额上限时的 cost 维（可辨识具体触发维度） */
    private String budgetExceededMessage(BudgetContext budget) {
        StringBuilder msg = new StringBuilder("预算耗尽: token=")
                .append(budget.getTokenConsumed()).append("/").append(budget.getTokenBudget());
        if (budget.getCostBudget() > 0) {
            msg.append(String.format(", cost=%.4f/%.4f元", budget.getCostConsumed(), budget.getCostBudget()));
        }
        return msg.toString();
    }

    // ==================== 内置工具执行（S9：plan_task / 文件工具） ====================

    private boolean isBuiltinTool(String name) {
        return PLAN_TOOL_NAME.equals(name)
                || SPAWN_TOOL_NAME.equals(name)
                || READ_FILE_TOOL.equals(name)
                || WRITE_FILE_TOOL.equals(name)
                || EDIT_FILE_TOOL.equals(name)
                || LIST_DIR_TOOL.equals(name)
                || ASK_TOOL_NAME.equals(name);
    }

    /** 同步路径内置工具分发（携带父预算：spawn 的子任务消耗需计入父账本） */
    private ToolResult executeBuiltinToolSync(ToolCall tc, ToolContext toolContext, BudgetContext parentBudget) {
        if (SPAWN_TOOL_NAME.equals(tc.getName())) {
            return executeSpawnAgentsSync(tc, toolContext, parentBudget);
        }
        if (PLAN_TOOL_NAME.equals(tc.getName())) {
            return executePlanToolSync(tc.getArguments(), toolContext);
        }
        if (ASK_TOOL_NAME.equals(tc.getName())) {
            // 同步路径（wenshi/子代理）无 SSE 与问答界面：不挂起，立即降级应答
            return ToolResult.builder().success(true)
                    .output("（当前为非交互执行模式，无法向用户提问；请基于现有信息自主决策并继续任务）")
                    .build();
        }
        return executeFileTool(tc, toolContext);
    }

    /** 流式路径内置工具分发：plan_task 发计划事件；spawn 发子代理进度事件；ask_user 挂起等待用户回答；文件工具走 SPI（阻塞 IO 调度到工具执行池） */
    private Flux<AgentEvent> executeBuiltinToolStream(ToolCall tc, ToolContext toolContext,
                                                      BudgetContext parentBudget, List<Message> messages) {
        if (SPAWN_TOOL_NAME.equals(tc.getName())) {
            return executeSpawnAgentsStream(tc, toolContext, parentBudget, messages);
        }
        if (PLAN_TOOL_NAME.equals(tc.getName())) {
            return executePlanToolStream(tc, toolContext, messages);
        }
        if (ASK_TOOL_NAME.equals(tc.getName())) {
            return executeAskUserStream(tc, toolContext, messages);
        }
        return Mono.fromFuture(CompletableFuture.supplyAsync(
                        () -> executeFileTool(tc, toolContext), config.getToolExecutor()))
                .map(result -> {
                    String output = result.isSuccess() ? result.getOutput() : result.getError();
                    appendToolMessage(messages, tc, output);
                    return toolResultEvent(tc, output);
                })
                .flux();
    }

    /** 文件工具执行（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 路由到会话工作空间 */
    private ToolResult executeFileTool(ToolCall tc, ToolContext toolContext) {
        if (fileWorkspace == null || !fileWorkspace.available()) {
            return ToolResult.builder().success(false).error("文件工具未配置").build();
        }
        String name = tc.getName();
        String args = tc.getArguments() != null ? tc.getArguments() : "{}";
        try {
            JsonNode root = objectMapper.readTree(args);
            String path = root.path("path").asText("");
            if (path.isBlank()) {
                return ToolResult.builder().success(false).error("缺少 path 参数").build();
            }
            switch (name) {
                case READ_FILE_TOOL -> {
                    String content = fileWorkspace.readFile(toolContext, path);
                    if (content == null) {
                        return ToolResult.builder().success(false).error("文件不存在: " + path).build();
                    }
                    // 内容截断保护（与外发工具同款输出上限策略，默认 10KB）
                    return ToolResult.builder().success(true).output(truncate(content, 10 * 1024)).build();
                }
                case WRITE_FILE_TOOL -> {
                    String content = root.path("content").asText("");
                    fileWorkspace.writeFile(toolContext, path, content);
                    return ToolResult.builder().success(true)
                            .output("已写入 " + path + "（" + content.length() + " 字符）").build();
                }
                case EDIT_FILE_TOOL -> {
                    String oldText = root.path("old_text").asText("");
                    String newText = root.path("new_text").asText("");
                    if (oldText.isEmpty()) {
                        return ToolResult.builder().success(false).error("缺少 old_text 参数").build();
                    }
                    String current = fileWorkspace.readFile(toolContext, path);
                    if (current == null) {
                        return ToolResult.builder().success(false).error("文件不存在: " + path).build();
                    }
                    int idx = current.indexOf(oldText);
                    if (idx < 0) {
                        return ToolResult.builder().success(false)
                                .error("未找到要替换的文本（old_text 不匹配）: " + path).build();
                    }
                    if (current.indexOf(oldText, idx + 1) >= 0) {
                        return ToolResult.builder().success(false)
                                .error("old_text 在文件中多处匹配，请提供更长的唯一上下文").build();
                    }
                    String updated = current.substring(0, idx) + newText + current.substring(idx + oldText.length());
                    fileWorkspace.writeFile(toolContext, path, updated);
                    // 起始行号与增删行数随结果下发：前端过程时间线据此渲染 zcode 风格差异块（+N -N / 红绿行号）
                    int startLine = 1;
                    for (int i = 0; i < idx; i++) {
                        if (current.charAt(i) == '\n') startLine++;
                    }
                    int[] diff = diffLineCounts(oldText, newText);
                    return ToolResult.builder().success(true)
                            .output("已编辑 " + path + "（第 " + startLine + " 行起，+" + diff[0] + " -" + diff[1] + "）").build();
                }
                case LIST_DIR_TOOL -> {
                    List<String> entries = fileWorkspace.listDir(toolContext, path);
                    if (entries.isEmpty()) {
                        return ToolResult.builder().success(true).output("(空目录)").build();
                    }
                    return ToolResult.builder().success(true)
                            .output(truncate(String.join("\n", entries), 4096)).build();
                }
                default -> {
                    return ToolResult.builder().success(false).error("未知内置工具: " + name).build();
                }
            }
        } catch (Exception e) {
            return ToolResult.builder().success(false).error("文件操作失败: " + e.getMessage()).build();
        }
    }

    /**
     * 行级增删统计（edit_file 结果展示）：公共前后缀裁剪 + 中段 LCS。
     * 口径与前端 lineDiff.ts 保持一致，返回 [新增行数, 删除行数]。
     */
    static int[] diffLineCounts(String oldText, String newText) {
        String[] a = oldText.split("\n", -1);
        String[] b = newText.split("\n", -1);
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int sufA = a.length;
        int sufB = b.length;
        while (sufA > pre && sufB > pre && a[sufA - 1].equals(b[sufB - 1])) {
            sufA--;
            sufB--;
        }
        int m = sufA - pre;
        int n = sufB - pre;
        if (m == 0) {
            return new int[]{n, 0};
        }
        if (n == 0) {
            return new int[]{0, m};
        }
        if ((long) m * n > 640_000) {
            return new int[]{n, m}; // 超大中段降级：整块删+增，避免 O(n²) 内存
        }
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                dp[i][j] = a[pre + i].equals(b[pre + j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int common = dp[0][0];
        return new int[]{n - common, m - common};
    }

    /** 流式路径执行 plan_task：发计划事件（首次创建/后续更新）+ 工具结果事件，并回灌 tool 消息 */
    private Flux<AgentEvent> executePlanToolStream(ToolCall tc, ToolContext toolContext, List<Message> messages) {
        boolean firstCall;
        try {
            JsonNode root = objectMapper.readTree(tc.getArguments() != null ? tc.getArguments() : "{}");
            ToolContext.PlanState state = planStateOf(toolContext);
            firstCall = state.getSteps().isEmpty();
            applyPlanState(state, root);
            // 计划正文落盘（可选 markdown 参数）：保存到会话工作空间 plan/ 目录，
            // 前端"查看完整计划"经文件内容接口读取预览；planPath 随计划事件与 done 快照透传
            String markdown = root.path("markdown").asText("");
            if (!markdown.isBlank() && fileWorkspace != null && fileWorkspace.available()) {
                try {
                    String planPath = "plan/plan-" + java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMdd-HHmmss")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.now()) + ".md";
                    fileWorkspace.writeFile(toolContext, planPath, markdown);
                    state.setPlanPath(planPath);
                } catch (Exception e) {
                    log.warn("计划文件写入失败（不影响计划清单）: {}", e.getMessage());
                }
            }
            String output = "任务清单已更新：" + state.getSteps().size() + " 项步骤"
                    + (state.getPlanPath() != null ? "（计划文件: " + state.getPlanPath() + "）" : "");
            appendToolMessage(messages, tc, output);
            AgentEvent.AgentEventBuilder eventBuilder = AgentEvent.builder()
                    .type(firstCall ? AgentEvent.PLAN_CREATED : AgentEvent.PLAN_UPDATED)
                    .planTitle(state.getTitle())
                    .plan(state.getSteps());
            if (state.getPlanPath() != null) {
                eventBuilder.metadata(new java.util.LinkedHashMap<>(java.util.Map.of("planPath", state.getPlanPath())));
            }
            return Flux.just(eventBuilder.build(), toolResultEvent(tc, output));
        } catch (Exception e) {
            log.warn("plan_task 解析失败: {}", e.getMessage());
            String output = "计划解析失败: " + e.getMessage();
            appendToolMessage(messages, tc, output);
            return Flux.just(toolResultEvent(tc, output));
        }
    }

    /**
     * ask_user（HITL 问答）：头发 ASK_USER 事件（问题+askId+选项），随后挂起等待用户回答。
     * 工具 Flux 不完成即整条流在此停留（下一轮推理不启动，心跳保活照常）；
     * 用户回答（或跳过/超时降级应答）到达后回灌为工具结果，自动续跑。
     */
    private Flux<AgentEvent> executeAskUserStream(ToolCall tc, ToolContext toolContext, List<Message> messages) {
        if (userInteractionGateway == null) {
            String fallback = "（用户交互通道未配置，请基于现有信息自主决策并继续任务）";
            appendToolMessage(messages, tc, fallback);
            return Flux.just(toolResultEvent(tc, fallback));
        }
        try {
            JsonNode root = objectMapper.readTree(tc.getArguments() != null ? tc.getArguments() : "{}");
            String question = root.path("question").asText("");
            java.util.List<String> options = new java.util.ArrayList<>();
            if (root.has("options") && root.get("options").isArray()) {
                root.get("options").forEach(n -> options.add(n.asText()));
            }
            if (question.isBlank()) {
                String output = "提问内容为空，已忽略（请基于现有信息继续任务）";
                appendToolMessage(messages, tc, output);
                return Flux.just(toolResultEvent(tc, output));
            }
            String askId = "ask-" + java.util.UUID.randomUUID();
            UserInteractionGateway.AskRequest request = UserInteractionGateway.AskRequest.builder()
                    .askId(askId)
                    .sessionId(toolContext.getSessionId())
                    .question(question)
                    .options(options)
                    .timeoutSeconds(config.getAskTimeoutSeconds())
                    .build();
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("askId", askId);
            meta.put("question", question);
            meta.put("options", options);
            AgentEvent askEvent = AgentEvent.builder()
                    .type(AgentEvent.ASK_USER)
                    .metadata(meta)
                    .build();
            return Flux.concat(
                    Flux.just(askEvent),
                    userInteractionGateway.askQuestion(request)
                            .map(answer -> {
                                appendToolMessage(messages, tc, answer);
                                return toolResultEvent(tc, answer);
                            })
                            .onErrorResume(e -> {
                                log.warn("ask_user 等待回答异常: {}", e.getMessage());
                                String output = "（提问通道异常，请基于现有信息继续任务）";
                                appendToolMessage(messages, tc, output);
                                return reactor.core.publisher.Mono.just(toolResultEvent(tc, output));
                            })
                            .flux());
        } catch (Exception e) {
            log.warn("ask_user 解析失败: {}", e.getMessage());
            String output = "提问解析失败: " + e.getMessage();
            appendToolMessage(messages, tc, output);
            return Flux.just(toolResultEvent(tc, output));
        }
    }

    /** 同步路径执行 plan_task：仅更新计划状态并返回工具结果（同步无事件流） */
    private ToolResult executePlanToolSync(String arguments, ToolContext toolContext) {
        try {
            JsonNode root = objectMapper.readTree(arguments != null ? arguments : "{}");
            ToolContext.PlanState state = planStateOf(toolContext);
            applyPlanState(state, root);
            return ToolResult.builder().success(true)
                    .output("任务清单已更新：" + state.getSteps().size() + " 项步骤")
                    .build();
        } catch (Exception e) {
            return ToolResult.builder().success(false).error("计划解析失败: " + e.getMessage()).build();
        }
    }

    // ==================== 子代理派生（spawn_subagents） ====================

    /** 单个子任务定义 */
    private record SubAgentSpec(String name, String agentId, String prompt) {}

    /** 单个子代理执行结果 */
    private record SubAgentResult(int index, String name, String agentId, boolean success,
                                  String output, long tokens, long durationMs) {}

    /** spawn 参数解析结果：error 非空表示校验失败 */
    private static final class SpawnRequest {
        final List<SubAgentSpec> agents = new ArrayList<>();
        String error;
    }

    /** 解析并校验 spawn_subagents 参数（数量/提示词长度上限，防御性拦截超规格派生） */
    private SpawnRequest parseSpawnRequest(String arguments) {
        SpawnRequest req = new SpawnRequest();
        try {
            JsonNode root = objectMapper.readTree(arguments != null ? arguments : "{}");
            JsonNode agentsNode = root.path("agents");
            if (!agentsNode.isArray() || agentsNode.isEmpty()) {
                req.error = "缺少 agents 数组参数";
                return req;
            }
            AgentEngineConfig.Subagents sub = config.getSubagents();
            int maxPerSpawn = sub != null ? Math.max(1, sub.getMaxPerSpawn()) : 5;
            int maxPromptChars = sub != null ? sub.getMaxPromptChars() : 8000;
            if (agentsNode.size() > maxPerSpawn) {
                req.error = "子代理数量超限（单次最多派生 " + maxPerSpawn + " 个）";
                return req;
            }
            int i = 0;
            for (JsonNode n : agentsNode) {
                i++;
                String prompt = n.path("prompt").asText("");
                if (prompt.isBlank()) {
                    req.error = "子代理[" + i + "] 缺少 prompt";
                    return req;
                }
                if (prompt.length() > maxPromptChars) {
                    req.error = "子代理[" + i + "] prompt 超长（上限 " + maxPromptChars + " 字符）";
                    return req;
                }
                req.agents.add(new SubAgentSpec(
                        n.path("name").asText("子任务" + i),
                        n.path("agentId").asText(null),
                        prompt));
            }
        } catch (Exception e) {
            req.error = "参数解析失败: " + e.getMessage();
        }
        return req;
    }

    /** 构建子任务：指定 agentId 或继承父；默认隔离会话（子任务 prompt 需自包含）；深度+1（防递归失控）；配额继承 */
    private AgentTask buildSubAgentTask(SubAgentSpec spec, ToolContext ctx) {
        AgentEngineConfig.Subagents sub = config.getSubagents();
        String subAgentId = (spec.agentId() != null && !spec.agentId().isBlank())
                ? spec.agentId() : ctx.getAgentId();
        // 子任务模型解析：子代理自身 AgentSpec 配置了模型则优先（task 级不设显式模型）；
        // 否则回退父任务已解析的 provider/model，保证子任务不会 MODEL_NOT_RESOLVED
        AgentSpec subSpec = loadAgent(subAgentId);
        boolean specHasModel = subSpec != null && subSpec.getModelProvider() != null
                && subSpec.getModelName() != null;
        boolean shareHistory = sub != null && sub.isShareSessionHistory();
        return AgentTask.builder()
                .agentId(subAgentId)
                .sessionId(shareHistory ? ctx.getSessionId() : null)
                .userId(ctx.getUserId())
                .message(spec.prompt())
                .agentDepth(ctx.getAgentDepth() + 1)
                .modelProvider(specHasModel ? null : ctx.getModelProvider())
                .modelName(specHasModel ? null : ctx.getModelName())
                .quotaTokenBudget(ctx.getQuotaTokenBudget())
                .quotaBlockEnabled(ctx.getQuotaBlockEnabled())
                .build();
    }

    /** 提交一批子代理并行执行：独立线程池（防嵌套 join 死锁）+ 单分支超时/异常降级为错误文本 */
    private List<CompletableFuture<SubAgentResult>> submitSubAgents(SpawnRequest request, ToolContext ctx) {
        AgentEngineConfig.Subagents sub = config.getSubagents();
        int timeoutSeconds = sub != null ? sub.getTimeoutSeconds() : 180;
        List<CompletableFuture<SubAgentResult>> futures = new ArrayList<>();
        int index = 0;
        for (SubAgentSpec spec : request.agents) {
            final int idx = index++;
            final AgentTask subTask = buildSubAgentTask(spec, ctx);
            futures.add(CompletableFuture
                    .supplyAsync(() -> {
                        long start = System.currentTimeMillis();
                        // 子任务走完整 execute()：独立预算/注入检测/截断自愈/经验沉淀全部生效
                        LlmResponse resp = execute(subTask);
                        long tokens = resp.getUsage() != null && resp.getUsage().getTotalTokens() != null
                                ? resp.getUsage().getTotalTokens()
                                : Math.max(1, (resp.getContent() == null ? 0 : resp.getContent().length()) / 3);
                        return new SubAgentResult(idx, spec.name(), subTask.getAgentId(), true,
                                resp.getContent(), tokens, System.currentTimeMillis() - start);
                    }, config.getSubAgentExecutor())
                    .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .handle((r, ex) -> {
                        if (ex != null) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            String reason = cause instanceof java.util.concurrent.TimeoutException
                                    ? "执行超时（" + timeoutSeconds + " 秒上限）"
                                    : String.valueOf(cause.getMessage());
                            log.warn("子代理[{}] {} 执行失败: {}", idx, spec.name(), reason);
                            return new SubAgentResult(idx, spec.name(), subTask.getAgentId(), false,
                                    reason, 0, 0);
                        }
                        return r;
                    }));
        }
        return futures;
    }

    /**
     * 聚合子代理结果：头部统计（成功/失败/耗时）+ 每分支独立小节（输出截断）；
     * 子任务 token 消耗折算入父预算（父熔断感知子消耗）；聚合文本过 PII 脱敏。
     * 返回 [聚合文本, 是否全部分支失败]。
     */
    private java.util.AbstractMap.SimpleEntry<String, Boolean> aggregateSpawnResults(
            ToolContext ctx, BudgetContext parentBudget,
            List<CompletableFuture<SubAgentResult>> futures, long startMs) {
        List<SubAgentResult> results = futures.stream().map(CompletableFuture::join).toList();
        return aggregateResults(ctx, parentBudget, results, startMs);
    }

    /** 聚合结果（流式路径复用）：从分支结果列表直接聚合（不再经 Future join） */
    private java.util.AbstractMap.SimpleEntry<String, Boolean> aggregateResults(
            ToolContext ctx, BudgetContext parentBudget,
            List<SubAgentResult> results, long startMs) {
        AgentEngineConfig.Subagents sub = config.getSubagents();
        int maxOutputChars = sub != null ? sub.getMaxOutputChars() : 10 * 1024;
        long totalTokens = 0;
        int failed = 0;
        for (SubAgentResult r : results) {
            totalTokens += r.tokens();
            if (!r.success()) {
                failed++;
            }
        }
        StringBuilder sb = new StringBuilder("子代理并行执行完成：共 ")
                .append(results.size()).append(" 个，成功 ").append(results.size() - failed)
                .append("，失败 ").append(failed)
                .append("，耗时 ").append((System.currentTimeMillis() - startMs) / 1000).append(" 秒");
        for (SubAgentResult r : results) {
            sb.append("\n\n## 子代理[").append(r.index()).append("] ").append(r.name())
                    .append("（").append(r.agentId() == null ? "默认" : r.agentId()).append("）");
            if (!r.success()) {
                sb.append("【执行失败】");
            }
            sb.append("\n").append(truncate(r.output(), maxOutputChars));
        }
        if (totalTokens > 0 && parentBudget != null) {
            budgetController.consume(parentBudget, totalTokens,
                    budgetController.calculateCost(ctx.getModelName(), totalTokens / 2, totalTokens - totalTokens / 2));
        }
        recordMetricSafe("agent.subagents.spawn", results.size(),
                Map.of("agentId", ctx.getAgentId() != null ? ctx.getAgentId() : "unknown",
                        "failed", String.valueOf(failed)));
        return new java.util.AbstractMap.SimpleEntry<>(
                outputSanitizer.checkOutput(sb.toString()), failed == results.size());
    }

    /** 深度护栏（运行时兜底）：即使模型幻觉调用未注册的 spawn 工具，也拒绝超深度派生 */
    private String depthGuardMessage(ToolContext toolContext) {
        AgentEngineConfig.Subagents sub = config.getSubagents();
        int maxDepth = sub != null ? sub.getMaxDepth() : 2;
        if (toolContext.getAgentDepth() + 1 > maxDepth) {
            return "已达子代理嵌套深度上限（" + maxDepth + "），请直接基于现有信息完成任务";
        }
        return null;
    }

    /** 同步路径 spawn：并行派发、等待全部完成、聚合为单个工具结果 */
    private ToolResult executeSpawnAgentsSync(ToolCall tc, ToolContext toolContext, BudgetContext parentBudget) {
        String depthError = depthGuardMessage(toolContext);
        if (depthError != null) {
            return ToolResult.builder().success(false).error(depthError).build();
        }
        SpawnRequest request = parseSpawnRequest(tc.getArguments());
        if (request.error != null) {
            return ToolResult.builder().success(false).error(request.error).build();
        }
        long start = System.currentTimeMillis();
        List<CompletableFuture<SubAgentResult>> futures = submitSubAgents(request, toolContext);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        var aggregation = aggregateSpawnResults(toolContext, parentBudget, futures, start);
        return ToolResult.builder()
                .success(!aggregation.getValue())
                .output(aggregation.getValue() ? null : aggregation.getKey())
                .error(aggregation.getValue() ? aggregation.getKey() : null)
                .build();
    }

    /**
     * 流式路径 spawn（二期）：各分支改为流式执行——分支内部事件打 subagentId/subagentName
     * 标签后 merge 透传（前端按 subagentId 分桶流式渲染右侧面板）；分支完成发
     * subagent_status（status=success/failed，携带分支结果文本供历史回放）；全部完成后
     * 聚合回灌 tool 消息并发 tool_result（含预算折算与 PII 脱敏，与同步路径一致）。
     */
    private Flux<AgentEvent> executeSpawnAgentsStream(ToolCall tc, ToolContext toolContext,
                                                      BudgetContext parentBudget, List<Message> messages) {
        String depthError = depthGuardMessage(toolContext);
        if (depthError != null) {
            appendToolMessage(messages, tc, depthError);
            return Flux.just(toolResultEvent(tc, depthError));
        }
        SpawnRequest request = parseSpawnRequest(tc.getArguments());
        if (request.error != null) {
            appendToolMessage(messages, tc, request.error);
            return Flux.just(toolResultEvent(tc, request.error));
        }
        return Flux.defer(() -> {
            long start = System.currentTimeMillis();
            AgentEngineConfig.Subagents sub = config.getSubagents();
            int timeoutSeconds = sub != null ? sub.getTimeoutSeconds() : 180;
            List<AgentEvent> startEvents = new ArrayList<>();
            List<Flux<AgentEvent>> branchFluxes = new ArrayList<>();
            List<BranchRun> runs = new ArrayList<>();
            int index = 0;
            for (SubAgentSpec spec : request.agents) {
                final int idx = index++;
                final String subagentId = "sub-" + idx + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                final long branchStart = System.currentTimeMillis();
                final AgentTask subTask = buildSubAgentTask(spec, toolContext);
                // 分支现场：正文累积 / token（done 事件）/ 失败标记 / 结束时间；聚合时定格为 SubAgentResult
                final BranchRun run = new BranchRun(idx, spec.name(), subTask.getAgentId(), branchStart);
                // 派生开始事件：前端时间线立即出现"子智能体 … 执行中"行
                startEvents.add(subagentStatusEvent(run.toResult(), subagentId, "running", null));
                Flux<AgentEvent> branch = executeStream(subTask)
                        .map(ev -> withSubagentTag(ev, subagentId, idx, spec.name()))
                        .doOnNext(ev -> {
                            if (AgentEvent.CONTENT.equals(ev.getType()) && ev.getContent() != null) {
                                run.output.append(ev.getContent());
                            }
                            if (AgentEvent.DONE.equals(ev.getType()) && ev.getMetadata() != null
                                    && ev.getMetadata().get("tokenEstimated") instanceof Number n) {
                                run.tokens = n.longValue();
                            }
                        })
                        .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                        .onErrorResume(e -> {
                            run.failed = true;
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            String reason = cause instanceof java.util.concurrent.TimeoutException
                                    ? "执行超时（" + timeoutSeconds + " 秒上限）"
                                    : String.valueOf(cause.getMessage());
                            log.warn("子代理[{}] {} 流式执行失败: {}", idx, spec.name(), reason);
                            run.output.append("【执行失败】").append(reason);
                            // 失败以分支内容事件透出（面板可见），同时计入聚合
                            return Flux.just(withSubagentTag(AgentEvent.builder()
                                    .type(AgentEvent.CONTENT)
                                    .content("⚠️ 子代理执行失败：" + reason)
                                    .build(), subagentId, idx, spec.name()));
                        })
                        .doFinally(sig -> run.endMs = System.currentTimeMillis());
                // 分支完成：subagent_status（携带结果文本，供历史回放聚合结果展示）
                branch = branch.concatWith(Flux.defer(() -> Flux.just(subagentStatusEvent(
                        run.toResult(), subagentId, run.failed ? "failed" : "success", null))));
                branchFluxes.add(branch);
                runs.add(run);
            }
            // 分支事件合并（按发生顺序交错，前端按 subagentId 分桶）
            Flux<AgentEvent> merged = Flux.merge(branchFluxes);
            // 聚合：全部完成后子消耗折算入父预算，回灌 tool 消息并发 tool_result（runs 为
            // 活引用，聚合时定格各分支最终状态）
            Flux<AgentEvent> aggregate = Mono.fromCallable(() -> {
                        List<SubAgentResult> results = runs.stream().map(BranchRun::toResult).toList();
                        var aggregation = aggregateResults(toolContext, parentBudget, results, start);
                        appendToolMessage(messages, tc, aggregation.getKey());
                        return toolResultEvent(tc, aggregation.getKey());
                    })
                    .flux();
            return Flux.concat(Flux.fromIterable(startEvents), merged, aggregate);
        });
    }

    /** 分支流式执行现场（结果在聚合时定格为 SubAgentResult） */
    private static final class BranchRun {
        final int index;
        final String name;
        final String agentId;
        final long startMs;
        final StringBuilder output = new StringBuilder();
        volatile long tokens;
        volatile boolean failed;
        volatile long endMs;

        BranchRun(int index, String name, String agentId, long startMs) {
            this.index = index;
            this.name = name;
            this.agentId = agentId;
            this.startMs = startMs;
        }

        SubAgentResult toResult() {
            long dur = Math.max(1, (endMs > 0 ? endMs : System.currentTimeMillis()) - startMs);
            return new SubAgentResult(index, name, agentId, !failed, output.toString(), tokens, dur);
        }
    }

    /** 事件打子代理标签（分支内部事件透传，前端按 subagentId 分桶流式渲染右侧面板） */
    private AgentEvent withSubagentTag(AgentEvent ev, String subagentId, int index, String name) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>(
                ev.getMetadata() == null ? Map.of() : ev.getMetadata());
        meta.put("subagentId", subagentId);
        meta.put("subagentName", name == null ? "" : name);
        meta.put("subagentIndex", index);
        return AgentEvent.builder()
                .type(ev.getType())
                .content(ev.getContent())
                .reasoning(ev.getReasoning())
                .toolCall(ev.getToolCall())
                .toolResult(ev.getToolResult())
                .finishReason(ev.getFinishReason())
                .planTitle(ev.getPlanTitle())
                .plan(ev.getPlan())
                .errorMessage(ev.getErrorMessage())
                .metadata(meta)
                .build();
    }

    private AgentEvent subagentStatusEvent(SubAgentResult r, String subagentId, String statusOverride, String resultOverride) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("subagentId", subagentId);
        meta.put("index", r.index());
        meta.put("name", r.name() == null ? "" : r.name());
        meta.put("agentId", r.agentId() == null ? "" : r.agentId());
        meta.put("status", statusOverride != null ? statusOverride : (r.success() ? "success" : "failed"));
        meta.put("durationMs", r.durationMs());
        meta.put("tokens", r.tokens());
        // 分支结果文本（截断）：历史回放点击子智能体行时右侧面板展示
        String result = resultOverride != null ? resultOverride : r.output();
        meta.put("result", result != null && result.length() > 2000 ? result.substring(0, 2000) : result);
        return AgentEvent.builder()
                .type(AgentEvent.SUBAGENT_STATUS)
                .content("子代理[" + r.index() + "] " + r.name()
                        + ("running".equals(statusOverride) ? " 开始执行" : (r.success() ? " 已完成" : " 失败")))
                .metadata(meta)
                .build();
    }

    private ToolContext.PlanState planStateOf(ToolContext toolContext) {
        if (toolContext.getPlanState() == null) {
            toolContext.setPlanState(new ToolContext.PlanState());
        }
        return toolContext.getPlanState();
    }

    private void applyPlanState(ToolContext.PlanState state, JsonNode root) {
        List<AgentEvent.PlanStepInfo> steps = new ArrayList<>();
        JsonNode stepsNode = root.path("steps");
        if (stepsNode.isArray()) {
            int i = 0;
            for (JsonNode s : stepsNode) {
                i++;
                steps.add(AgentEvent.PlanStepInfo.builder()
                        .id(s.path("id").asText(String.valueOf(i)))
                        .text(s.path("text").asText(""))
                        .status(s.path("status").asText("pending"))
                        .build());
            }
        }
        String title = root.path("title").asText("");
        if (!title.isBlank()) {
            state.setTitle(title);
        }
        state.setSteps(steps);
    }

    private void appendToolMessage(List<Message> messages, ToolCall tc, String output) {
        synchronized (messages) {
            messages.add(Message.builder()
                    .role("tool")
                    .content(output)
                    .toolCallId(tc.getId())
                    .name(tc.getName())
                    .build());
        }
    }

    private AgentEvent toolResultEvent(ToolCall tc, String output) {
        return AgentEvent.builder()
                .type(AgentEvent.TOOL_RESULT)
                .toolResult(AgentEvent.ToolResultInfo.builder()
                        .toolCallId(tc.getId())
                        .name(tc.getName())
                        .result(output)
                        .build())
                .build();
    }

    /**
     * done 事件：携带 finishReason、最终任务计划快照（S9 F5，前端历史回放用），
     * 以及执行统计 metadata（完成透明度：前端据此区分"AI 自主收尾"与"被限制收尾"）。
     */
    private AgentEvent doneEvent(ToolContext toolContext, String finishReason, BudgetContext budget) {
        AgentEvent.AgentEventBuilder builder = AgentEvent.builder()
                .type(AgentEvent.DONE)
                .finishReason(finishReason);
        ToolContext.PlanState state = toolContext != null ? toolContext.getPlanState() : null;
        // metadata 合并构建：planPath 与预算统计共存于同一 Map（builder.metadata 为整体覆盖语义）
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (state != null && state.getPlanPath() != null) {
            meta.put("planPath", state.getPlanPath());
        }
        if (state != null && !state.getSteps().isEmpty()) {
            builder.planTitle(state.getTitle())
                    .plan(state.getSteps());
        }
        if (budget != null) {
            meta.put("rounds", budget.getCurrentRound());
            meta.put("elapsedMs", budget.getElapsedMs());
            meta.put("tokenEstimated", budget.getTokenConsumed());
            meta.put("timeRenewals", budget.getTimeRenewals());
            meta.put("roundsRenewed", budget.getRoundsRenewed());
            meta.put("tokenUnlimited", budget.isTokenUnlimited());
            builder.metadata(meta);
        } else if (!meta.isEmpty()) {
            builder.metadata(meta);
        }
        return builder.build();
    }

    // ==================== 辅助方法 ====================

    private AgentSpec loadAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return persistenceService.loadAgent(agentId);
    }

    private String[] resolveProviderAndModel(AgentSpec agent, AgentTask task) {
        if (task.getModelProvider() != null && task.getModelName() != null) {
            return new String[]{task.getModelProvider(), task.getModelName()};
        }
        if (agent != null && agent.getModelProvider() != null && agent.getModelName() != null) {
            return new String[]{agent.getModelProvider(), agent.getModelName()};
        }
        throw AgentEngineException.of("MODEL_NOT_RESOLVED",
                "无法解析 LLM 供应商与模型，请在 AgentTask 或 AgentSpec 中指定 modelProvider 与 modelName");
    }

    /**
     * 共用前置决策链：感知 -> 复杂度路由 -> 按复杂度创建预算 -> 模型路由。
     * 同步与流式两条路径共用，保证两条路径产出一致的 provider/model 决策。
     */
    private ExecutionPlan planExecution(AgentTask task, String[] pm) {
        // 感知引擎：原始输入 -> 结构化意图（结果缓存到 task 供后续环节使用）
        PerceptionEngine.Intent intent = perceptionEngine.perceive(task.getMessage());
        task.setIntent(intent);
        // 复杂度路由：意图 + 描述特征 -> System 1/2 决策
        ComplexityRouter.ComplexityResult complexity = complexityRouter.route(task.getMessage(), intent);
        // 会话对话的复杂度预算下限（用户实报 199s/30s=665% 熔断）：路由按消息文本评分，
        // 「继续执行未完成的任务」这类短消息会被判 L1（30s 时间预算），但多轮会话
        // 携带完整任务上下文且带工具循环，实际是重任务——session 非空时至少 L2。
        // L1 只保留给无会话的裸轻量调用。
        String budgetLevel = complexity.getLevel();
        if (task.getSessionId() != null && !task.getSessionId().isBlank() && "L1".equals(budgetLevel)) {
            budgetLevel = "L2";
            log.info("复杂度预算下限提升: 会话任务 L1 -> L2 (sessionId={})", task.getSessionId());
        }
        // 按复杂度等级创建预算
        BudgetContext budget = budgetController.createBudget(budgetLevel);
        // 用户套餐配额注入（配额体系）：余量作为本次执行的 token 上限；
        // 熔断开关（用户偏好）决定 token 耗尽是熔断还是仅告警。null=不限制
        if (task.getQuotaTokenBudget() != null && task.getQuotaTokenBudget() > 0) {
            budget.setTokenBudget(task.getQuotaTokenBudget());
            // 不限量哨兵（未绑定套餐用户下发 Long.MAX_VALUE/2）标记：告警文案据此省略
            // token 维——哨兵利用率恒 ≈0，显示"token 0%"会让用户误读为记账故障（用户实报）
            budget.setTokenUnlimited(task.getQuotaTokenBudget() >= Long.MAX_VALUE / 4);
        }
        if (task.getQuotaBlockEnabled() != null) {
            budget.setBlockEnabled(task.getQuotaBlockEnabled());
        }
        // 模型路由：调用方未显式指定模型时按复杂度/预算选择最优模型
        String[] routed = routeModelIfApplicable(task, pm, complexity, budget);
        return new ExecutionPlan(intent, complexity, budget, routed[0], routed[1]);
    }

    /** 一次执行的前置决策结果：意图、复杂度、预算与最终 provider/model。 */
    private static final class ExecutionPlan {
        final PerceptionEngine.Intent intent;
        final ComplexityRouter.ComplexityResult complexity;
        final BudgetContext budget;
        final String provider;
        final String model;

        ExecutionPlan(PerceptionEngine.Intent intent, ComplexityRouter.ComplexityResult complexity,
                      BudgetContext budget, String provider, String model) {
            this.intent = intent;
            this.complexity = complexity;
            this.budget = budget;
            this.provider = provider;
            this.model = model;
        }
    }

    /**
     * 模型路由：调用方显式指定模型时尊重调用方；否则按复杂度评分与剩余预算
     * 咨询 {@link ModelSelector} 选择最优模型。路由失败静默保持原模型。
     */
    private String[] routeModelIfApplicable(AgentTask task, String[] pm,
                                            ComplexityRouter.ComplexityResult complexity,
                                            BudgetContext budget) {
        // 显式模型时默认尊重调用方；modelRouteEnabled=true（基准评测/按需）除外——
        // 仍咨询 ModelSelector，命中更优模型则改选
        boolean routingRequested = Boolean.TRUE.equals(task.getModelRouteEnabled());
        if (!routingRequested && task.getModelProvider() != null && task.getModelName() != null) {
            return pm;
        }
        try {
            long budgetRemaining = budget != null
                    ? Math.max(0, budget.getTokenBudget() - budget.getTokenConsumed())
                    : Long.MAX_VALUE;
            ModelSelector.ModelSelection selection = modelSelector.select(
                    task.getMessage(),
                    complexity != null ? complexity.getScore() : 5,
                    null, 0, budgetRemaining);
            if (selection != null && selection.getModelName() != null
                    && !selection.getModelName().isBlank() && !selection.getModelName().equals(pm[1])) {
                String provider = selection.getModelProvider() != null ? selection.getModelProvider() : pm[0];
                log.info("模型路由: {} -> {} (provider={}, reason={})",
                        pm[1], selection.getModelName(), provider, selection.getReason());
                recordMetricSafe("agent.model.route", 1, Map.of(
                        "from", pm[1], "to", selection.getModelName(),
                        "agentId", task.getAgentId() != null ? task.getAgentId() : "default"));
                return new String[]{provider, selection.getModelName()};
            }
        } catch (Exception e) {
            log.debug("模型路由失败，保持原模型: {}", e.getMessage());
        }
        return pm;
    }

    private List<Message> buildMessages(AgentSpec agent, AgentTask task) {
        List<Message> history = task.getHistory();
        if (history == null && task.getSessionId() != null) {
            history = sessionContextService.buildContextMessages(task.getSessionId(), config.getDefaultHistoryLimit());
        }
        List<Message> messages = messageBuilder.buildMessages(agent, task.getMessage(), history,
                task.getAgentMode(), task.getThinkingStyle());
        // 注入记忆上下文（记忆路由 SPI 按需注入相关记忆到 system 消息）
        String domain = task.getAgentId() != null ? task.getAgentId() : "default";
        return memoryRouter.inject(domain, messages, task.getMessage());
    }

    private List<ToolDefinition> buildToolDefinitions(AgentTask task, Map<String, ToolConfig> toolConfigMap) {
        String agentId = task.getAgentId();
        List<ToolDefinition> definitions = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            List<ToolConfig> tools = persistenceService.loadAgentTools(agentId);
            tools.stream()
                    .peek(tool -> toolConfigMap.put(tool.getToolName(), tool))
                    .map(tool -> ToolDefinition.builder()
                            .name(tool.getToolName())
                            .description(tool.getDescription())
                            .parameters(tool.getRequestSchema())
                            .build())
                    .forEach(definitions::add);
        }
        // 内置任务计划工具（S9 F5）：模型自主创建/更新任务清单，前端渲染任务流程卡片
        definitions.add(ToolDefinition.builder()
                .name(PLAN_TOOL_NAME)
                .description(PLAN_TOOL_DESCRIPTION)
                .parameters(PLAN_TOOL_SCHEMA)
                .build());
        // 内置子代理派生工具：仅顶层/未达深度上限的任务可派生（防递归失控）
        AgentEngineConfig.Subagents sub = config.getSubagents();
        if (sub != null && sub.isEnabled() && task.getAgentDepth() < sub.getMaxDepth()) {
            definitions.add(ToolDefinition.builder()
                    .name(SPAWN_TOOL_NAME)
                    .description(SPAWN_TOOL_DESCRIPTION)
                    .parameters(SPAWN_TOOL_SCHEMA_TEMPLATE.formatted(Math.max(1, sub.getMaxPerSpawn())))
                    .build());
        }
        // 内置用户问答工具（HITL）：仅顶层任务注册（子代理内提问无法到达用户界面，且会挂起分支）；
        // 网关未装配时工具自动降级应答
        if (task.getAgentDepth() == 0) {
            definitions.add(ToolDefinition.builder()
                    .name(ASK_TOOL_NAME)
                    .description(ASK_TOOL_DESCRIPTION)
                    .parameters(ASK_TOOL_SCHEMA)
                    .build());
        }
        // 内置文件工具（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 在会话工作空间执行
        if (fileWorkspace != null && fileWorkspace.available()) {
            definitions.add(ToolDefinition.builder().name(READ_FILE_TOOL)
                    .description("读取工作空间中的文件内容（相对路径）。")
                    .parameters(READ_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(WRITE_FILE_TOOL)
                    .description("创建或覆盖工作空间中的文件（整体写入）。")
                    .parameters(WRITE_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(EDIT_FILE_TOOL)
                    .description("精确编辑文件中的一段文本（old_text 需在文件中唯一匹配）。")
                    .parameters(EDIT_FILE_SCHEMA).build());
            definitions.add(ToolDefinition.builder().name(LIST_DIR_TOOL)
                    .description("列出工作空间目录中的文件与子目录。")
                    .parameters(LIST_DIR_SCHEMA).build());
        }
        return definitions;
    }

    private ToolContext buildToolContext(AgentTask task, AgentSpec agent, String provider, String model) {
        boolean sandboxEnabled = false;
        String sandboxImage = null;

        if (agent != null && agent.getModelConfig() != null && !agent.getModelConfig().isBlank()) {
            try {
                JsonNode configNode = objectMapper.readTree(agent.getModelConfig());
                if (configNode.has("sandboxEnabled")) {
                    sandboxEnabled = configNode.get("sandboxEnabled").asBoolean(false);
                }
                if (configNode.has("sandboxImage")) {
                    sandboxImage = configNode.get("sandboxImage").asText(null);
                }
            } catch (Exception e) {
                log.warn("解析 Agent modelConfig 失败: agentId={}", agent.getId(), e);
            }
        }

        return ToolContext.builder()
                .userId(task.getUserId())
                .sessionId(task.getSessionId())
                .agentId(task.getAgentId())
                .timeout(30)
                .sandboxEnabled(sandboxEnabled)
                .sandboxImage(sandboxImage)
                // 子代理派生上下文：深度/已解析模型/配额继承（spawn_subagents 构建子任务用）
                .agentDepth(task.getAgentDepth())
                .modelProvider(provider)
                .modelName(model)
                .quotaTokenBudget(task.getQuotaTokenBudget())
                .quotaBlockEnabled(task.getQuotaBlockEnabled())
                .build();
    }

    private double resolveTemperature(AgentTask task) {
        if (task.getTemperature() != null) {
            return task.getTemperature();
        }
        return PromptDirective.getTemperatureByMode(task.getAgentMode());
    }

    private int resolveMaxTokens(AgentTask task) {
        if (task != null && task.getMaxTokens() != null && task.getMaxTokens() > 0) {
            return task.getMaxTokens();
        }
        return config.getDefaultMaxTokens();
    }

    private void accumulateToolCall(Map<String, ToolCallAccumulator> accumulators,
                                    LlmChunk.ToolCallDelta delta) {
        String key;
        if (delta.getId() != null) {
            key = delta.getId();
        } else if (!accumulators.isEmpty()) {
            // 标准 OpenAI 流式协议：id 仅在工具调用首个分块出现，后续分块无 id，
            // 需按顺序关联到最近一次出现的工具调用（accumulators 为插入有序的 LinkedHashMap）
            key = accumulators.keySet().stream().reduce((first, second) -> second).orElse("default");
        } else {
            key = "default";
        }
        ToolCallAccumulator acc = accumulators.computeIfAbsent(key, k -> new ToolCallAccumulator());
        if (delta.getId() != null) {
            acc.id = delta.getId();
        }
        if (delta.getName() != null) {
            acc.name = delta.getName();
        }
        if (delta.getArguments() != null) {
            acc.arguments.append(delta.getArguments());
        }
    }

    private List<ToolCall> assembleToolCalls(Map<String, ToolCallAccumulator> accumulators) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators.values()) {
            if (acc.name != null) {
                toolCalls.add(ToolCall.builder()
                        .id(acc.id)
                        .name(acc.name)
                        .arguments(acc.arguments.toString())
                        .build());
            }
        }
        return toolCalls;
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 将执行经验存储到记忆系统（记忆存储 SPI）。
     * <p>执行完成后自动将用户输入与 AI 输出作为情景记忆持久化，
     * 供后续任务的记忆检索与经验复用使用。
     */
    private void storeExperience(AgentTask task, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        try {
            String domain = task.getAgentId() != null ? task.getAgentId() : "default";
            String content = "用户: " + truncate(task.getMessage(), 500)
                    + "\nAI: " + truncate(output, 1000);
            MemoryFragment fragment = MemoryFragment.builder()
                    .domain(domain)
                    .type("episodic")
                    .content(content)
                    .build();
            memoryStore.store(fragment);
            log.debug("storeExperience: domain={}, contentLength={}", domain, content.length());
        } catch (Exception e) {
            log.debug("storeExperience failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 记录执行成功：追踪 + 指标（任务成功 + 预算利用率）。
     */
    private void recordSuccess(AgentTask task, BudgetContext budget) {
        try {
            String agentId = task.getAgentId() != null ? task.getAgentId() : "unknown";
            traceService.recordTrace(budget != null ? "budget" : "exec", agentId, "EXECUTE", "llm_complete", "success");
            metricService.recordMetric("agent.task.success", budget != null ? budget.getElapsedMs() : 0,
                    Map.of("agentId", agentId));
            if (budget != null) {
                metricService.recordMetric("agent.budget.utilization", budget.getTokenConsumed(),
                        Map.of("agentId", agentId, "budget", String.valueOf(budget.getTokenBudget())));
            }
        } catch (Exception e) {
            log.debug("recordSuccess failed: {}", e.getMessage());
        }
    }

    /**
     * 记录执行失败：追踪 + 指标（任务失败）。
     */
    private void recordFailure(AgentTask task, String reason) {
        try {
            String agentId = task.getAgentId() != null ? task.getAgentId() : "unknown";
            traceService.recordTrace(agentId, agentId, "EXECUTE", "error", reason);
            metricService.recordMetric("agent.task.failure", 1, Map.of("agentId", agentId, "reason", reason));
        } catch (Exception e) {
            log.debug("recordFailure failed: {}", e.getMessage());
        }
    }

    /** 指标记录（缓存/路由/熔断等观测点，失败静默） */
    private void recordMetricSafe(String name, double value, Map<String, String> tags) {
        try {
            metricService.recordMetric(name, value, tags);
        } catch (Exception e) {
            log.debug("recordMetric failed: {} {}", name, e.getMessage());
        }
    }
}
