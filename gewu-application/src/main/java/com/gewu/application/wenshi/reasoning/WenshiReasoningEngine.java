package com.gewu.application.wenshi.reasoning;

import com.gewu.application.wenshi.knowledge.MemoryInjector;
import com.gewu.application.wenshi.knowledge.MemoryRouter;
import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.domain.wenshi.learning.ReasoningTrace;
import com.gewu.application.wenshi.learning.ExperienceExtractor;
import com.gewu.application.wenshi.learning.ReflectionAgent;
import com.gewu.application.agent.ToolExecutionService;
import com.gewu.application.agent.ToolContext;
import com.gewu.application.agent.dto.ToolResult;
import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.wenshi.search.WebSearchFragment;
import com.gewu.application.wenshi.search.WebSearchResult;
import com.gewu.application.wenshi.search.WebSearchService;
import com.gewu.application.wenshi.search.WebResultVerifier;
import com.gewu.application.wenshi.output.ContentClassifier;
import com.gewu.application.wenshi.output.FileInfo;
import com.gewu.application.wenshi.output.FileOutputService;
import com.gewu.application.wenshi.output.OutputDecision;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.mapper.wenshi.ReasoningTraceMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wenshi 推理引擎 — 三层认知编排的核心调度器。
 * <p>
 * 处理流程：Planner（任务分解）→ SolverRouter（策略选择）→ Critic（结果验证）。
 * 支持经验优先路由：若经验库存在相似任务则直接复用策略，跳过 LLM 推理以降低成本。
 * 推理完成后自动持久化推理轨迹，用于后续经验沉淀与模型优化。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WenshiReasoningEngine {

    /** 任务规划器，负责将复杂任务分解为可执行的子目标序列 */
    private final Planner planner;

    /** 策略路由器，根据子目标特征选择最优求解策略 */
    private final SolverRouter solverRouter;

    /** 结果验证器，对求解结果进行多层校验 */
    private final Critic critic;

    /** 记忆路由器，根据任务类型路由到对应的记忆存储 */
    private final MemoryRouter memoryRouter;

    /** 记忆注入器，将检索到的记忆注入推理上下文 */
    private final MemoryInjector memoryInjector;

    /** 推理轨迹持久化映射器 */
    private final ReasoningTraceMapper traceMapper;

    /** LLM 客户端工厂，用于 LLM_REASONING 策略 */
    private final LlmClientFactory llmClientFactory;

    /** 工具执行服务，用于 TOOL_EXECUTION 策略 */
    private final ToolExecutionService toolExecutionService;

    /** 经验映射器，用于 EXPERIENCE_REUSE 策略 */
    private final ExperienceMapper experienceMapper;

    /** 嵌入适配器，用于 EXPERIENCE_REUSE 策略的向量化 */
    private final EmbeddingAdapter embeddingAdapter;

    /** 经验抽取器，用于推理后学习 */
    private final ExperienceExtractor experienceExtractor;

    /** 反思代理，用于失败经验分析 */
    private final ReflectionAgent reflectionAgent;

    /** 网络搜索服务，用于 WEB_SEARCH 策略 */
    private final WebSearchService webSearchService;

    /** 网络搜索结果正确性判断器，用于搜索结果验证 */
    private final WebResultVerifier webResultVerifier;

    /** 模型配置服务，用于从 model ID 解析 LLM 提供商 */
    private final ModelConfigService modelConfigService;

    /** 内容分类器，判定推理结果是直接输出还是保存为文件 */
    private final ContentClassifier contentClassifier;

    /** 文件输出服务，将复杂内容保存为文件并生成摘要 */
    private final FileOutputService fileOutputService;

    /** JSON 解析器，用于解析工具调用参数 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 默认 LLM 提供商 */
    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    /** 默认 LLM 模型名称 */
    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    /** 是否对 LLM_REASONING 自动增强网络搜索 */
    @Value("${gewu.wenshi.web-search.auto-enhance:true}")
    private boolean autoEnhanceWebSearch;

    /** 本地语义记忆少于此条数才触发自动增强 */
    @Value("${gewu.wenshi.web-search.auto-enhance-min-kb:3}")
    private int autoEnhanceMinKb;

    /**
     * 执行同步推理。
     * <p>
     * 完整流程：任务分解 → 记忆路由 → 子目标执行 → 结果验证 → 轨迹持久化。
     *
     * @param request 推理请求，包含用户消息、上下文及约束条件；不可为 null
     * @return 推理结果，包含最终答案、执行计划、推理轨迹及统计信息
     * @throws IllegalArgumentException 当 request 为 null 时抛出
     * @since 1.0.0
     */
    public WenshiReasoningResult reason(WenshiReasoningRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("WenshiReasoningEngine.reason: agentId={}, message={}", request.getAgentId(), request.getMessage());

        // 第一步：任务分解，生成子目标执行树
        WenshiReasoningResult.PlanTree plan = planner.plan(request.getMessage(), request);

        // 记录规划阶段的推理轨迹
        List<WenshiReasoningResult.TraceStep> trace = new ArrayList<>();
        trace.add(WenshiReasoningResult.TraceStep.builder()
                .step(1).phase("PLAN").action("任务分解")
                .detail("分解为 " + plan.getSubgoals().size() + " 个子目标")
                .timestamp(System.currentTimeMillis()).build());

        // 第二步：任务分类与记忆路由，确定需要检索的知识类型
        TaskType taskType = TaskType.classify(request.getMessage());
        MemoryRouter.RoutingResult routing = memoryRouter.route(
                request.getTenantId(), request.getUserId(), taskType.getCode(), request.getMessage());

        // 第三步：准备记忆注入计划（注入到 callLlm 的系统提示中）
        MemoryInjector.InjectionPlan injection = memoryInjector.prepareInjection(routing);

        // 第四步：按序执行子目标，每个子目标独立选择策略
        Object solution = executeSubgoals(plan.getSubgoals(), request, routing, trace);

        // 第五步：结果验证，确保输出质量达标
        Critic.CriticResult criticResult = critic.evaluate(
                Critic.Solution.builder().output(solution).build(), request);

        // 记录验证阶段轨迹
        trace.add(WenshiReasoningResult.TraceStep.builder()
                .step(trace.size() + 1).phase("CRITIC").action("结果验证")
                .detail("通过: " + criticResult.isPassed())
                .timestamp(System.currentTimeMillis()).build());

        long reasoningTime = System.currentTimeMillis() - startTime;

        // 组装最终结果
        WenshiReasoningResult result = WenshiReasoningResult.builder()
                .answer(solution != null ? solution.toString() : "")
                .plan(plan)
                .trace(trace)
                .reasoningTimeMs(reasoningTime)
                .fromExperience(false)
                .tokenStats(WenshiReasoningResult.TokenStatistics.builder()
                        .llmCallCount(1).build())
                .build();

        // 异步持久化推理轨迹并触发学习，不影响主流程响应
        ReasoningTrace savedTrace = saveTrace(request, result);
        triggerLearningAsync(savedTrace);

        return result;
    }

    /**
     * 执行流式推理。
     * <p>
     * 将推理过程拆分为多个阶段，逐阶段输出结果块：
     * <ol>
     *   <li>规划阶段：输出任务分解结果</li>
     *   <li>记忆路由阶段：输出记忆检索摘要</li>
     *   <li>求解阶段：对每个子目标调用 LLM 流式输出</li>
     *   <li>完成阶段：输出结束标记</li>
     * </ol>
     *
     * @param request 推理请求；不可为 null
     * @return 包含推理结果块的 Flux 流
     * @since 1.0.0
     */
    public Flux<WenshiReasoningChunk> reasonStream(WenshiReasoningRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("WenshiReasoningEngine.reasonStream: agentId={}, message={}", request.getAgentId(), request.getMessage());

        // 第一步：任务分解
        WenshiReasoningResult.PlanTree plan = planner.plan(request.getMessage(), request);

        // 第二步：记忆路由
        TaskType taskType = TaskType.classify(request.getMessage());
        MemoryRouter.RoutingResult routing = memoryRouter.route(
                request.getTenantId(), request.getUserId(), taskType.getCode(), request.getMessage());

        // 第三步：准备记忆注入
        MemoryInjector.InjectionPlan injection = memoryInjector.prepareInjection(routing);

        // 构建系统提示
        String systemPrompt = "你是格物知行 AI 助手。请根据以下知识上下文回答用户问题。";
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            systemPrompt += "\n\n【用户原始问题】" + request.getMessage()
                    + "\n请在回答中严格遵守用户原始问题中的所有技术约束（如编程语言、框架版本等）。";
        }
        if (injection.getSummary() != null && !injection.getSummary().isBlank()) {
            systemPrompt += "\n\n" + injection.getSummary();
        }

        // 逐个子目标流式输出
        List<WenshiReasoningResult.SubgoalNode> subgoals = plan.getSubgoals();
        StringBuilder accumulated = new StringBuilder();

        // 规划阶段输出
        Flux<WenshiReasoningChunk> planFlux = Flux.just(WenshiReasoningChunk.builder()
                .type("content")
                .content("正在分析您的问题...\n")
                .build());

        // 发射任务分解思考过程
        StringBuilder planThinking = new StringBuilder("📋 任务分解：将问题分解为 " + subgoals.size() + " 个子目标\n");
        for (int i = 0; i < subgoals.size(); i++) {
            WenshiReasoningResult.SubgoalNode sg = subgoals.get(i);
            planThinking.append(i + 1).append(". ").append(sg.getDescription());
            if (sg.getStrategy() != null) {
                planThinking.append(" [").append(sg.getStrategy()).append("]");
            }
            planThinking.append("\n");
        }
        planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                .type("thinking")
                .reasoning(planThinking.toString())
                .build()));

        // 每个子目标的流式输出
        for (WenshiReasoningResult.SubgoalNode subgoal : subgoals) {
            SolverRouter.Strategy strategy = solverRouter.selectStrategy(subgoal, request);

            // 发射策略选择思考过程
            planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                    .type("thinking")
                    .reasoning("🔍 执行子目标：" + subgoal.getDescription() + " [策略：" + strategy + "]\n")
                    .build()));

            if (strategy == SolverRouter.Strategy.KNOWLEDGE_LOOKUP
                    && routing.getSemanticMemories() != null
                    && !routing.getSemanticMemories().isEmpty()) {
                // 知识查找：直接输出记忆内容
                for (SemanticFragment fragment : routing.getSemanticMemories()) {
                    if (fragment.getContent() != null) {
                        accumulated.append(fragment.getContent());
                        planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                                .type("content")
                                .content(fragment.getContent())
                                .build()));
                    }
                }
            } else if (strategy == SolverRouter.Strategy.WEB_SEARCH) {
                // 网络：发射搜索开始事件
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("web_search_start")
                        .content("正在搜索网络资源...")
                        .build()));

                // 执行搜索 + 正确性判断
                List<WebSearchFragment> verified = new ArrayList<>();
                try {
                    WebSearchResult searchResult = webSearchService.search(subgoal.getDescription());
                    if (searchResult.isSuccess() && searchResult.getFragments() != null) {
                        verified = webResultVerifier.verify(
                                searchResult.getFragments(), subgoal.getDescription());
                        // 注入到 routing 供后续 LLM 子目标复用（知识融合）
                        routing.setWebSearchResults(verified);
                    }
                } catch (Exception e) {
                    log.warn("reasonStream: web search failed: {}", e.getMessage());
                }

                // 发射正确性判断中事件
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("web_verifying")
                        .content("正在验证搜索结果正确性...")
                        .build()));

                // 统计采纳/丢弃
                int adoptedCount = 0;
                int discardedCount = 0;
                for (WebSearchFragment f : verified) {
                    if (f.isAdopted() && !f.isDiscarded()) {
                        adoptedCount++;
                    } else if (f.isDiscarded()) {
                        discardedCount++;
                    }
                }

                // 发射搜索结果事件
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("web_search_result")
                        .webSearch(WenshiReasoningChunk.WebSearchInfo.builder()
                                .query(subgoal.getDescription())
                                .results(verified.stream()
                                        .map(f -> WenshiReasoningChunk.SearchItem.builder()
                                                .url(f.getUrl())
                                                .title(f.getTitle())
                                                .snippet(f.getSnippet())
                                                .source(f.getSource())
                                                .adopted(f.isAdopted())
                                                .discarded(f.isDiscarded())
                                                .discardReason(f.getDiscardReason())
                                                .confidence(f.getConfidence())
                                                .build())
                                        .toList())
                                .adoptedCount(adoptedCount)
                                .discardedCount(discardedCount)
                                .build())
                        .build()));

                // 发射验证结论事件
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("web_verdict")
                        .verify(WenshiReasoningChunk.VerifyInfo.builder()
                                .method(verified.isEmpty() ? "NONE" : verified.get(0).getVerifyMethod())
                                .query(subgoal.getDescription())
                                .totalResults(verified.size())
                                .adoptedCount(adoptedCount)
                                .discardedCount(discardedCount)
                                .build())
                        .build()));

                // 输出采纳结果作为正文内容（减少 LLM 调用）
                for (WebSearchFragment f : verified) {
                    if (f.isAdopted() && !f.isDiscarded() && f.getSnippet() != null) {
                        String content = "[" + f.getSource() + "] " + f.getSnippet();
                        accumulated.append(content);
                        planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                                .type("content")
                                .content(content)
                                .build()));
                    }
                }

                // 更新 systemPrompt 以纳入新注入的网络知识（后续 LLM 子目标可复用）
                MemoryInjector.InjectionPlan refreshedInjection = memoryInjector.prepareInjection(routing);
                StringBuilder refreshedPrompt = new StringBuilder(
                        "你是格物知行 AI 助手。请根据以下知识上下文回答用户问题。");
                if (refreshedInjection.getSummary() != null && !refreshedInjection.getSummary().isBlank()) {
                    refreshedPrompt.append("\n\n").append(refreshedInjection.getSummary());
                }
                systemPrompt = refreshedPrompt.toString();
            } else if (strategy == SolverRouter.Strategy.FILE_OUTPUT) {
                // FILE_OUTPUT：同步调用 LLM 生成完整内容，不流式输出到对话框
                // 内容积累到 accumulated，在循环结束后统一分类+保存文件+输出摘要
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("status")
                        .content("正在生成内容...")
                        .build()));
                try {
                    String fullContent = callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
                    accumulated.append(fullContent);
                    // 发射核心内容摘要到思考过程（让用户看到推理产出，但不占用对话框正文）
                    String coreSummary = extractCoreSummary(fullContent);
                    planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                            .type("thinking")
                            .reasoning(coreSummary)
                            .build()));
                } catch (Exception e) {
                    log.warn("reasonStream: FILE_OUTPUT LLM 调用失败: {}", e.getMessage());
                    accumulated.append("LLM 推理失败: ").append(e.getMessage());
                }
            } else {
                // LLM 推理：自动增强网络搜索上下文（若启用且本地知识不足）
                if (shouldAutoEnhance(request, routing)) {
                    autoEnhanceWithContext(subgoal.getDescription(), routing);
                }
                // 构建 systemPrompt，注入记忆摘要 + 用户原始问题约束
                MemoryInjector.InjectionPlan refreshedInjection = memoryInjector.prepareInjection(routing);
                StringBuilder llmPrompt = new StringBuilder(
                        "你是格物知行 AI 助手。请根据以下知识上下文回答用户问题。");
                if (request.getMessage() != null && !request.getMessage().isBlank()) {
                    llmPrompt.append("\n\n【用户原始问题】").append(request.getMessage())
                            .append("\n请在回答中严格遵守用户原始问题中的所有技术约束（如编程语言、框架版本等）。");
                }
                if (refreshedInjection.getSummary() != null && !refreshedInjection.getSummary().isBlank()) {
                    llmPrompt.append("\n\n").append(refreshedInjection.getSummary());
                }
                systemPrompt = llmPrompt.toString();
                // 流式调用 LLM
                planFlux = planFlux.concatWith(streamLlmCall(systemPrompt, subgoal.getDescription(), accumulated, request.getModel()));
            }
        }

        // 内容分类：判定是否需要保存为文件
        OutputDecision decision = contentClassifier.classify(accumulated.toString(), plan.getSubgoals());
        if (decision.getType() == OutputDecision.DecisionType.FILE_OUTPUT) {
            try {
                // 生成文件
                List<FileInfo> fileInfos = fileOutputService.generateFiles(
                        decision.getFiles(), request.getProjectId(), request.getRequirementId());
                // 发射文件卡片事件
                for (FileInfo fi : fileInfos) {
                    planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                            .type("file")
                            .file(WenshiReasoningChunk.FileInfo.builder()
                                    .fileName(fi.getFileName())
                                    .fileType(fi.getFileType())
                                    .mimeType(fi.getMimeType())
                                    .fileSize(fi.getFileSize())
                                    .downloadUrl(fi.getDownloadUrl())
                                    .previewContent(fi.getPreviewContent())
                                    .source(fi.getSource())
                                    .build())
                            .build()));
                }
                // 生成摘要并替换对话框内容
                String summary = fileOutputService.generateSummary(decision.getFullContent(), request.getModel());
                accumulated.setLength(0);
                accumulated.append(summary);
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("content")
                        .content(summary)
                        .build()));
            } catch (Exception e) {
                // 文件生成失败时回退为直接输出原始内容，确保会话内容不丢失
                log.warn("reasonStream: 文件生成失败，回退为直接输出: {}", e.getMessage(), e);
                // FILE_OUTPUT 子目标的内容未发射 content 事件，需补发
                planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                        .type("content")
                        .content(accumulated.toString())
                        .build()));
            }
        }

        // 完成标记
        long reasoningTime = System.currentTimeMillis() - startTime;
        planFlux = planFlux.concatWith(Flux.just(WenshiReasoningChunk.builder()
                .type("done")
                .build()));

        // 异步持久化并触发学习
        planFlux = planFlux.doOnComplete(() -> {
            WenshiReasoningResult result = WenshiReasoningResult.builder()
                    .answer(accumulated.toString())
                    .plan(plan)
                    .reasoningTimeMs(reasoningTime)
                    .fromExperience(false)
                    .build();
            ReasoningTrace savedTrace = saveTrace(request, result);
            triggerLearningAsync(savedTrace);
        });

        return planFlux;
    }

    /**
     * 流式调用 LLM。
     *
     * @param systemPrompt 系统提示
     * @param userMessage 用户消息
     * @param accumulated 累积器，用于收集完整输出
     * @return 包含流式内容块的 Flux
     * @since 1.0.0
     */
    private Flux<WenshiReasoningChunk> streamLlmCall(String systemPrompt, String userMessage, StringBuilder accumulated, String model) {
        try {
            String[] pm = resolveProviderAndModel(model);
            LlmClient client = llmClientFactory.getClient(pm[0]);

            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder().role("system").content(systemPrompt).build());
            messages.add(Message.builder().role("user").content(userMessage).build());

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(pm[1])
                    .messages(messages)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .stream(true)
                    .build();

            return client.chatStream(llmRequest)
                    .map(chunk -> {
                        if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                            accumulated.append(chunk.getDelta());
                            return WenshiReasoningChunk.builder()
                                    .type("content")
                                    .content(chunk.getDelta())
                                    .build();
                        }
                        return WenshiReasoningChunk.builder().type("content").content("").build();
                    })
                    .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isEmpty());
        } catch (Exception e) {
            log.warn("WenshiReasoningEngine.streamLlmCall failed: {}", e.getMessage());
            return Flux.just(WenshiReasoningChunk.builder()
                    .type("error")
                    .errorMessage("LLM 流式调用失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 按序执行子目标列表。
     * <p>
     * 每个子目标独立选择策略并执行，执行完成后标记为已完成。
     * 子目标间存在依赖关系，需按拓扑顺序依次执行。
     *
     * @param subgoals 待执行的子目标列表
     * @param request 原始推理请求
     * @param routing 记忆路由结果
     * @param trace 推理轨迹记录器，用于追加执行步骤
     * @return 所有子目标执行结果的拼接字符串
     * @since 1.0.0
     */
    private Object executeSubgoals(List<WenshiReasoningResult.SubgoalNode> subgoals,
                                    WenshiReasoningRequest request,
                                    MemoryRouter.RoutingResult routing,
                                    List<WenshiReasoningResult.TraceStep> trace) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < subgoals.size(); i++) {
            WenshiReasoningResult.SubgoalNode subgoal = subgoals.get(i);
            // 根据子目标特征动态选择最优策略
            SolverRouter.Strategy strategy = solverRouter.selectStrategy(subgoal, request);
            trace.add(traceStep(i + 2, strategy, subgoal.getDescription()));
            result.append(executeSingleSubgoal(strategy, subgoal, routing, request));
            subgoal.setCompleted(true);
        }
        return result.toString();
    }

    /**
     * 构建推理轨迹步骤。
     *
     * @param step 步骤序号
     * @param strategy 使用的求解策略
     * @param description 子目标描述
     * @return 轨迹步骤对象
     * @since 1.0.0
     */
    private WenshiReasoningResult.TraceStep traceStep(int step, SolverRouter.Strategy strategy, String description) {
        return WenshiReasoningResult.TraceStep.builder()
                .step(step).phase("SOLVE")
                .action("子目标: " + description)
                .detail("策略: " + strategy)
                .timestamp(System.currentTimeMillis()).build();
    }

/**
     * 根据策略类型执行单个子目标。
     * <p>
     * 策略优先级：知识查找 > 网络搜索 > 工具执行 > 经验复用 > LLM 推理兜底。
     * <p>
     * LLM_REASONING 分支支持网络搜索自动增强：当本地语义记忆不足且启用网络搜索时，
     * 先搜索相关资料注入上下文，再调用 LLM，减少模型幻觉和重试 token 消耗。
     *
     * @param strategy 求解策略枚举
     * @param subgoal 子目标节点
     * @param routing 记忆路由结果，用于知识查找策略
     * @param request 原始推理请求，用于构建工具上下文
     * @return 子目标执行结果字符串
     * @since 1.0.0
     */
    private String executeSingleSubgoal(SolverRouter.Strategy strategy,
                                          WenshiReasoningResult.SubgoalNode subgoal,
                                          MemoryRouter.RoutingResult routing,
                                          WenshiReasoningRequest request) {
        switch (strategy) {
            case KNOWLEDGE_LOOKUP: {
                // 直接从语义记忆中获取最相关的知识片段
                List<SemanticFragment> fragments = routing.getSemanticMemories();
                if (fragments != null && !fragments.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (SemanticFragment fragment : fragments) {
                        if (fragment.getContent() != null) {
                            sb.append(fragment.getContent()).append("\n");
                        }
                    }
                    return sb.toString().trim();
                }
                // 语义记忆为空时退化为 LLM 推理
                return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
            }
            case WEB_SEARCH:
                return executeWebSearchForSubgoal(subgoal, routing, request);
            case TOOL_EXECUTION:
                return executeToolForSubgoal(subgoal, request, routing);
            case FILE_OUTPUT:
                // 同步调用 LLM 生成内容，后续由 reasonStream 统一分类+保存文件
                return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
            case EXPERIENCE_REUSE:
                return executeExperienceReuse(subgoal, request, routing);
            case LLM_REASONING:
            default:
                // 自动增强：若启用网络搜索且本地语义记忆不足，先搜索相关资料注入上下文
                if (shouldAutoEnhance(request, routing)) {
                    autoEnhanceWithContext(subgoal.getDescription(), routing);
                }
                // 兜底策略：使用 LLM 进行推理（可能已注入网络相关知识）
                return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
        }
    }

    /**
     * 执行网络搜索子目标 — 搜索 → 正确性判断 → 知识融合 → 输出采纳结果。
     * <p>
     * 流程：
     * <ol>
     *   <li>调用 {@link WebSearchService#search} 获取搜索结果</li>
     *   <li>通过 {@link WebResultVerifier#verify} 四级漏斗判断正确性</li>
     *   <li>采纳结果注入 routing（供后续 LLM 子目标复用，实现知识融合）</li>
     *   <li>采纳结果直接拼接输出（减少 LLM 调用），无采纳则回退 LLM</li>
     * </ol>
     * 网络搜索失败或无采纳结果时兜底回退到 LLM 推理，确保系统可用性。
     *
     * @param subgoal 网络搜索子目标
     * @param routing 记忆路由结果，用于注入网络搜索结果和 LLM 回退
     * @param request 推理请求，用于读取约束条件
     * @return 采纳的搜索结果拼接，或 LLM 回退结果
     * @since 1.0.0
     */
    private String executeWebSearchForSubgoal(WenshiReasoningResult.SubgoalNode subgoal,
                                              MemoryRouter.RoutingResult routing,
                                              WenshiReasoningRequest request) {
        try {
            // 1. 执行网络搜索
            WebSearchResult searchResult = webSearchService.search(subgoal.getDescription());
            if (!searchResult.isSuccess() || searchResult.getFragments() == null
                    || searchResult.getFragments().isEmpty()) {
                log.warn("executeWebSearchForSubgoal: no results, fallback to LLM: {}", searchResult.getErrorMessage());
                return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
            }

            // 2. 四级漏斗正确性判断
            List<WebSearchFragment> verified = webResultVerifier.verify(
                    searchResult.getFragments(), subgoal.getDescription());

            // 3. 注入到 routing 供后续 LLM_REASONING 子目标使用（知识融合）
            routing.setWebSearchResults(verified);

            // 4. 拼接采纳结果直接输出（减少 LLM 调用）
            StringBuilder adoptedContent = new StringBuilder();
            long adopted = 0;
            long discarded = 0;
            for (WebSearchFragment f : verified) {
                if (f.isAdopted() && !f.isDiscarded()) {
                    adoptedContent.append("[").append(f.getSource()).append("] ")
                            .append(f.getSnippet()).append("\n");
                    adopted++;
                } else if (f.isDiscarded()) {
                    discarded++;
                }
            }

            log.info("executeWebSearchForSubgoal: query={}, total={}, adopted={}, discarded={}",
                    subgoal.getDescription(), verified.size(), adopted, discarded);

            if (!adoptedContent.isEmpty()) {
                return adoptedContent.toString().trim();
            }

            // 5. 无采纳结果则回退 LLM（带空网络知识注入）
            log.warn("executeWebSearchForSubgoal: no adopted results, fallback to LLM");
            return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
        } catch (Exception e) {
            log.warn("executeWebSearchForSubgoal failed, fallback to LLM: {}", e.getMessage());
            return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
        }
    }

    /**
     * 判断是否应对 LLM_REASONING 子目标做网络搜索自动增强。
     * <p>
     * 条件：
     * <ul>
     *   <li>启用网络搜索（配置 + 约束条件）</li>
     *   <li>本地语义记忆不足（少于 {@link #autoEnhanceMinKb} 条）</li>
     *   <li>本子目标执行链路尚未产生网络搜索结果（routing.webSearchResults 为 null）</li>
     * </ul>
     * 自动增强可减少 LLM 幻觉导致的重试 token 消耗。
     *
     * @param request 推理请求
     * @param routing 记忆路由结果
     * @return true 表示应执行自动增强
     * @since 1.0.0
     */
    private boolean shouldAutoEnhance(WenshiReasoningRequest request, MemoryRouter.RoutingResult routing) {
        if (!autoEnhanceWebSearch) {
            return false;
        }
        if (request.getConstraints() != null && !request.getConstraints().isEnableWebSearch()) {
            return false;
        }
        int localKbSize = routing.getSemanticMemories() == null ? 0 : routing.getSemanticMemories().size();
        return localKbSize < autoEnhanceMinKb && routing.getWebSearchResults() == null;
    }

    /**
     * 对 LLM_REASONING 子目标自动增强网络搜索上下文。
     * <p>
     * 执行搜索 + 正确性判断，仅注入采纳结果到 routing 供 {@link #callLlm} 的 MemoryInjector 消费，
     * 丢弃结果不进入 LLM 上下文，避免错误知识污染推理。
     *
     * @param query 子目标描述（作为搜索关键词）
     * @param routing 记忆路由结果，搜索结果会被注入其 webSearchResults 字段
     * @since 1.0.0
     */
    private void autoEnhanceWithContext(String query, MemoryRouter.RoutingResult routing) {
        try {
            WebSearchResult searchResult = webSearchService.search(query);
            if (!searchResult.isSuccess() || searchResult.getFragments() == null
                    || searchResult.getFragments().isEmpty()) {
                log.debug("autoEnhanceWithContext: no results for query={}", query);
                return;
            }
            List<WebSearchFragment> verified = webResultVerifier.verify(
                    searchResult.getFragments(), query);
            // 仅注入采纳结果，丢弃的不给 LLM
            List<WebSearchFragment> adopted = new ArrayList<>();
            for (WebSearchFragment f : verified) {
                if (f.isAdopted() && !f.isDiscarded()) {
                    adopted.add(f);
                }
            }
            if (!adopted.isEmpty()) {
                routing.setWebSearchResults(adopted);
                log.debug("autoEnhanceWithContext: injected {} adopted fragments for query={}",
                        adopted.size(), query);
            }
        } catch (Exception e) {
            log.debug("autoEnhanceWithContext failed, continuing with LLM only: {}", e.getMessage());
        }
    }

    /**
     * 为子目标执行工具调用。
     * <p>
     * 从子目标描述中解析工具名和参数，构建工具上下文并调用 ToolExecutionService。
     * 支持两种描述格式：(1) JSON {"tool":"name","args":"..."}；(2) 纯文本（整段作为工具名）。
     * 工具执行失败时回退到 LLM 推理。
     *
     * @param subgoal 子目标节点
     * @param request 推理请求
     * @param routing 记忆路由结果（用于 LLM 回退）
     * @return 工具执行输出或 LLM 回退结果
     */
    private String executeToolForSubgoal(WenshiReasoningResult.SubgoalNode subgoal,
                                          WenshiReasoningRequest request,
                                          MemoryRouter.RoutingResult routing) {
        String description = subgoal.getDescription();
        String toolName = description;
        String arguments = "{}";

        // 尝试从描述中解析 JSON 格式的工具调用
        try {
            if (description.trim().startsWith("{")) {
                JsonNode node = OBJECT_MAPPER.readTree(description);
                if (node.has("tool")) {
                    toolName = node.get("tool").asText();
                }
                if (node.has("args")) {
                    arguments = node.get("args").toString();
                }
            }
        } catch (Exception e) {
            log.debug("executeToolForSubgoal: description is not JSON, using as tool name: {}", description);
        }

        // 构建工具上下文
        ToolContext ctx = ToolContext.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .agentId(request.getAgentId())
                .sandboxEnabled(true)
                .build();

        try {
            ToolResult result = toolExecutionService.executeTool(toolName, arguments, ctx);
            if (result.isSuccess() && result.getOutput() != null) {
                log.info("executeToolForSubgoal: tool={}, success, outputLength={}", toolName, result.getOutput().length());
                return result.getOutput();
            } else {
                log.warn("executeToolForSubgoal: tool={}, failed: {}", toolName, result.getError());
                // 工具执行失败，回退到 LLM 推理
                return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
            }
        } catch (Exception e) {
            log.warn("executeToolForSubgoal: tool={}, exception: {}", toolName, e.getMessage());
            // 工具不存在或执行异常，回退到 LLM 推理
            return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
        }
    }

    /**
     * 经验复用执行：从经验库中检索相似场景的解决方案。
     * <p>
     * 将子目标描述向量化，查询 wenshi_experience 表中最相似的经验。
     * 若命中则增加命中计数并返回经验中的策略（解决方案）；否则回退到 LLM 推理。
     *
     * @param subgoal 子目标节点
     * @param request 推理请求（提供 tenantId）
     * @param routing 记忆路由结果（用于 LLM 回退）
     * @return 经验策略或 LLM 回退结果
     */
    private String executeExperienceReuse(WenshiReasoningResult.SubgoalNode subgoal,
                                           WenshiReasoningRequest request,
                                           MemoryRouter.RoutingResult routing) {
        String tenantId = request.getTenantId();
        if (tenantId == null) {
            return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
        }
        try {
            float[] queryVector = embeddingAdapter.embed(subgoal.getDescription());
            String vectorStr = toVectorString(queryVector);
            List<Experience> matches = experienceMapper.searchByVector(vectorStr, tenantId, 1);
            if (!matches.isEmpty()) {
                Experience best = matches.get(0);
                // 增加命中计数
                experienceMapper.incrementHitCount(best.getId());
                log.info("executeExperienceReuse: hit, scenario={}, score={}", best.getScenario(), best.getScore());
                if (best.getStrategy() != null) {
                    return best.getStrategy();
                }
            }
        } catch (Exception e) {
            log.warn("executeExperienceReuse: query failed, falling back to LLM: {}", e.getMessage());
        }
        return callLlm(subgoal.getDescription(), routing, request.getModel(), request.getMessage());
    }

    /**
     * 将浮点数组转换为 pgvector 字面量格式 [0.1,0.2,...]。
     */
    private String toVectorString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 调用 LLM 执行推理。
     * <p>
     * 将记忆注入的摘要作为系统提示，子目标描述作为用户消息，调用 LLM 生成答案。
     *
     * @param subgoalDescription 子目标描述
     * @param routing 记忆路由结果，用于注入上下文
     * @param model 前端选择的模型 ID，用于解析 LLM 提供商
     * @return LLM 生成的回答文本
     * @since 1.0.0
     */
    private String callLlm(String subgoalDescription, MemoryRouter.RoutingResult routing, String model, String originalMessage) {
        try {
            String[] pm = resolveProviderAndModel(model);
            LlmClient client = llmClientFactory.getClient(pm[0]);

            List<Message> messages = new ArrayList<>();

            // 构建系统提示，注入记忆摘要和用户原始问题（确保 LLM 知道完整上下文和约束）
            MemoryInjector.InjectionPlan injection = memoryInjector.prepareInjection(routing);
            String systemPrompt = "你是格物知行 AI 助手。请根据以下知识上下文回答用户问题。";
            if (originalMessage != null && !originalMessage.isBlank()) {
                systemPrompt += "\n\n【用户原始问题】" + originalMessage
                        + "\n请在回答中严格遵守用户原始问题中的所有技术约束（如编程语言、框架版本等）。";
            }
            if (injection.getSummary() != null && !injection.getSummary().isBlank()) {
                systemPrompt += "\n\n" + injection.getSummary();
            }
            messages.add(Message.builder().role("system").content(systemPrompt).build());

            // 用户消息：当前子目标任务
            messages.add(Message.builder().role("user").content(subgoalDescription).build());

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(pm[1])
                    .messages(messages)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .stream(false)
                    .build();

            LlmResponse response = client.chat(llmRequest);
            return response.getContent() != null ? response.getContent() : "";
        } catch (Exception e) {
            log.warn("WenshiReasoningEngine.callLlm failed: {}", e.getMessage());
            return "LLM 推理失败: " + e.getMessage();
        }
    }

    /**
     * 从完整内容中提取核心摘要（用于思考过程展示，不占用对话框正文）。
     * <p>
     * 提取策略：
     * <ul>
     *   <li>代码块：每个提取前 10 行 + 总行数</li>
     *   <li>标题结构：列出所有 H1~H3 标题</li>
     *   <li>无结构化内容：截取前 500 字符</li>
     * </ul>
     */
    private String extractCoreSummary(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder sb = new StringBuilder();

        // 提取代码块核心片段
        java.util.regex.Pattern codePattern = java.util.regex.Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");
        java.util.regex.Matcher m = codePattern.matcher(content);
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2);
            String[] lines = code.split("\n");
            int limit = Math.min(10, lines.length);
            sb.append("核心代码（").append(lang != null && !lang.isBlank() ? lang : "text")
              .append("，共").append(lines.length).append("行）：\n");
            sb.append("```").append(lang).append("\n");
            for (int i = 0; i < limit; i++) {
                sb.append(lines[i]).append("\n");
            }
            if (lines.length > limit) {
                sb.append("// ... 剩余 ").append(lines.length - limit).append(" 行\n");
            }
            sb.append("```\n\n");
        }

        // 提取标题结构
        java.util.regex.Pattern headerPattern = java.util.regex.Pattern.compile("^#{1,3}\\s+.+$", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher hm = headerPattern.matcher(content);
        boolean hasHeaders = false;
        while (hm.find()) {
            if (!hasHeaders) {
                sb.append("文档结构：\n");
                hasHeaders = true;
            }
            sb.append(hm.group()).append("\n");
        }

        if (sb.length() == 0) {
            return content.length() > 500 ? content.substring(0, 500) + "..." : content;
        }
        return sb.toString();
    }

    /**
     * 根据模型 ID 解析 LLM 提供商和模型名称。
     * <p>
     * 优先从数据库 model_config 表查询 provider_code，
     * 查不到时回退到默认配置（defaultLlmProvider / defaultLlmModel）。
     *
     * @param model 前端传入的模型 ID（如 LongCat-2.0、qwen-plus）
     * @return [providerCode, modelName] 数组
     * @since 1.0.0
     */
    private String[] resolveProviderAndModel(String model) {
        if (model != null && !model.isBlank()) {
            String providerCode = modelConfigService.getProviderCodeByModelId(model);
            if (providerCode != null) {
                return new String[]{providerCode, model};
            }
        }
        // 兜底：使用默认配置
        return new String[]{defaultLlmProvider, defaultLlmModel};
    }

    /**
     * 持久化推理轨迹。
     * <p>
     * 轨迹记录失败不影响主流程，仅输出警告日志。
     * 轨迹数据用于后续经验沉淀、模型微调及推理质量分析。
     *
     * @param request 原始推理请求
     * @param result 推理结果
     * @return 持久化后的推理轨迹（供学习层使用），持久化失败时返回 null
     * @since 1.0.0
     */
    private ReasoningTrace saveTrace(WenshiReasoningRequest request, WenshiReasoningResult result) {
        try {
            ReasoningTrace trace = new ReasoningTrace();
            trace.setId(com.gewu.common.ulid.Ulid.next());
            trace.setTenantId(request.getTenantId() != null ? request.getTenantId() : "default");
            trace.setSessionId(request.getSessionId());
            trace.setTaskId(request.getSessionId() != null ? request.getSessionId() : com.gewu.common.ulid.Ulid.next());
            trace.setTraceSteps(result.getTrace() != null ? result.getTrace().toString() : null);
            trace.setReasoningMs(result.getReasoningTimeMs());
            trace.setFromExperience(result.isFromExperience());
            traceMapper.insert(trace);
            return trace;
        } catch (RuntimeException e) {
            // 轨迹持久化失败不应阻塞推理结果返回
            log.warn("WenshiReasoningEngine.saveTrace failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 异步触发学习层：从推理轨迹中抽取经验，对低分经验进行反思。
     * <p>
     * 在独立线程中执行，不阻塞主推理流程。异常仅记录日志。
     *
     * @param trace 推理轨迹（saveTrace 返回值）
     */
    private void triggerLearningAsync(ReasoningTrace trace) {
        if (trace == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Experience exp = experienceExtractor.extract(trace);
                if (exp != null && ("FAIL".equals(exp.getOutcome())
                        || exp.getScore().compareTo(BigDecimal.valueOf(0.6)) < 0)) {
                    reflectionAgent.reflect(exp.getId());
                }
                log.info("triggerLearningAsync: experience extracted, id={}, outcome={}",
                        exp.getId(), exp.getOutcome());
            } catch (Exception e) {
                log.warn("triggerLearningAsync: learning failed: {}", e.getMessage());
            }
        });
    }
}
