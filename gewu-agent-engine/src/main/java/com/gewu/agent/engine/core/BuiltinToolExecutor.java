package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.hitl.UserInteractionGateway;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.spi.AgentSpec;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 内置工具执行器（AE-P2-1 三期切片）：内置五件套的执行逻辑，与 {@link BuiltinToolCatalog}
 *（目录管"是什么"）配对——本类管"怎么跑"。从 ReactAgentExecutor 外移（三期步1起分步迁移）。
 * <p><b>语义不变量</b>：内置工具由执行器直接分发执行，<b>不经过</b> ToolExecutor 五段
 * 安全管线——其操作对象是会话工作空间、用户交互与子代理派生等引擎内受控能力。
 * <p>协作方式：持 owner（ReactAgentExecutor）引用以复用共享 helper（truncate 等，
 * 包级可见），分步迁移期保持行为逐字节一致。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@Slf4j
class BuiltinToolExecutor {

    private final ReactAgentExecutor owner;
    private final com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace;
    private final com.gewu.agent.engine.hitl.UserInteractionGateway userInteractionGateway;
    private final ObjectMapper objectMapper;

    BuiltinToolExecutor(ReactAgentExecutor owner,
                        com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace,
                        com.gewu.agent.engine.hitl.UserInteractionGateway userInteractionGateway,
                        ObjectMapper objectMapper) {
        this.owner = owner;
        this.fileWorkspace = fileWorkspace;
        this.userInteractionGateway = userInteractionGateway;
        this.objectMapper = objectMapper;
    }

    /** 文件工具执行（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 路由到会话工作空间 */
    ToolResult executeFileTool(com.gewu.agent.engine.llm.model.ToolCall tc, ToolContext toolContext) {
        if (fileWorkspace == null || !fileWorkspace.available()) {
            return ToolResult.builder().success(false).error("文件工具未配置").build();
        }
        String name = tc.getName();
        String args = tc.getArguments() != null ? tc.getArguments() : "{}";
        try {
            JsonNode root = objectMapper.readTree(args);
            String path = root.path("path").asText("");
            // list_dir 的 path 可省略（默认工作空间根，与 LIST_DIR_SCHEMA required:[] 一致）
            if (path.isBlank() && !BuiltinToolCatalog.LIST_DIR_TOOL.equals(name)) {
                return ToolResult.builder().success(false).error("缺少 path 参数").build();
            }
            switch (name) {
                case BuiltinToolCatalog.READ_FILE_TOOL -> {
                    String content = fileWorkspace.readFile(toolContext, path);
                    if (content == null) {
                        return ToolResult.builder().success(false).error(
                                "文件不存在: " + path
                                        + "（path 应为相对当前工作空间根目录的路径，不要以 / 开头或加 workspace/ 前缀；"
                                        + "@global/ 前缀可只读访问全局资源；可用 list_dir 查看根目录内容后重试）").build();
                    }
                    // 内容截断保护（与外发工具同款输出上限策略，默认 10KB）
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    owner.truncate(content, 10 * 1024), toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.WRITE_FILE_TOOL -> {
                    String content = root.path("content").asText("");
                    fileWorkspace.writeFile(toolContext, path, content);
                    ReactAgentExecutor.invalidateNestedProbeCache(toolContext, path);
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    "已写入 " + path + "（" + content.length() + " 字符）", toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.EDIT_FILE_TOOL -> {
                    String oldText = root.path("old_text").asText("");
                    String newText = root.path("new_text").asText("");
                    if (oldText.isEmpty()) {
                        return ToolResult.builder().success(false).error("缺少 old_text 参数").build();
                    }
                    String current = fileWorkspace.readFile(toolContext, path);
                    if (current == null) {
                        return ToolResult.builder().success(false).error(
                                "文件不存在: " + path
                                        + "（path 应为相对当前工作空间根目录的路径，不要以 / 开头或加 workspace/ 前缀；"
                                        + "可用 list_dir 查看根目录内容后重试）").build();
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
                    int[] diff = ReactAgentExecutor.diffLineCounts(oldText, newText);
                    ReactAgentExecutor.invalidateNestedProbeCache(toolContext, path);
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    "已编辑 " + path + "（第 " + startLine + " 行起，+" + diff[0] + " -" + diff[1] + "）",
                                    toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.LIST_DIR_TOOL -> {
                    java.util.List<String> entries = fileWorkspace.listDir(toolContext, path);
                    if (entries.isEmpty()) {
                        return ToolResult.builder().success(true).output("(空目录)").build();
                    }
                    return ToolResult.builder().success(true)
                            .output(owner.truncate(String.join("\n", entries), 4096)).build();
                }
                default -> {
                    return ToolResult.builder().success(false).error("未知内置工具: " + name).build();
                }
            }
        } catch (Exception e) {
            log.warn("文件工具执行失败: err={}", e.getMessage());
            // 通用文案（CR L3-SEC-020 邻域：原始异常可能含沙箱内部路径，不进模型上下文）
            return ToolResult.builder().success(false).error("文件操作失败，请重试或调整参数").build();
        }
    }

    /** 流式路径执行 plan_task：发计划事件（首次创建/后续更新）+ 工具结果事件，并回灌 tool 消息 */
    Flux<AgentEvent> executePlanToolStream(com.gewu.agent.engine.llm.model.ToolCall tc, ToolContext toolContext,
                                           List<Message> messages) {
        boolean firstCall;
        try {
            JsonNode root = objectMapper.readTree(tc.getArguments() != null ? tc.getArguments() : "{}");
            ToolContext.PlanState state = owner.planStateOf(toolContext);
            firstCall = state.getSteps().isEmpty();
            owner.applyPlanState(state, root);
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
            owner.appendToolMessage(messages, tc, output);
            AgentEvent.AgentEventBuilder eventBuilder = AgentEvent.builder()
                    .type(firstCall ? AgentEvent.PLAN_CREATED : AgentEvent.PLAN_UPDATED)
                    .planTitle(state.getTitle())
                    .plan(state.getSteps());
            if (state.getPlanPath() != null) {
                eventBuilder.metadata(new java.util.LinkedHashMap<>(java.util.Map.of("planPath", state.getPlanPath())));
            }
            return Flux.just(eventBuilder.build(), owner.toolResultEvent(tc, output));
        } catch (Exception e) {
            log.warn("plan_task 解析失败: {}", e.getMessage());
            String output = "计划解析失败: " + e.getMessage();
            owner.appendToolMessage(messages, tc, output);
            return Flux.just(owner.toolResultEvent(tc, output));
        }
    }

    /** 同步路径执行 plan_task：仅更新计划状态并返回工具结果（同步无事件流） */
    ToolResult executePlanToolSync(String arguments, ToolContext toolContext) {
        try {
            JsonNode root = objectMapper.readTree(arguments != null ? arguments : "{}");
            ToolContext.PlanState state = owner.planStateOf(toolContext);
            owner.applyPlanState(state, root);
            return ToolResult.builder().success(true)
                    .output("任务清单已更新：" + state.getSteps().size() + " 项步骤")
                    .build();
        } catch (Exception e) {
            return ToolResult.builder().success(false).error("计划解析失败: " + e.getMessage()).build();
        }
    }
    /**
     * ask_user（HITL 问答）：头发 ASK_USER 事件（问题+askId+选项），随后挂起等待用户回答。
     * 工具 Flux 不完成即整条流在此停留（下一轮推理不启动，心跳保活照常）；
     * 用户回答（或跳过/超时降级应答）到达后回灌为工具结果，自动续跑。
     */
    Flux<AgentEvent> executeAskUserStream(ToolCall tc, ToolContext toolContext, List<Message> messages) {
        if (userInteractionGateway == null) {
            String fallback = "（用户交互通道未配置，请基于现有信息自主决策并继续任务）";
            owner.appendToolMessage(messages, tc, fallback);
            return Flux.just(owner.toolResultEvent(tc, fallback));
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
                owner.appendToolMessage(messages, tc, output);
                return Flux.just(owner.toolResultEvent(tc, output));
            }
            String askId = "ask-" + java.util.UUID.randomUUID();
            UserInteractionGateway.AskRequest request = UserInteractionGateway.AskRequest.builder()
                    .askId(askId)
                    .sessionId(toolContext.getSessionId())
                    .question(question)
                    .options(options)
                    .timeoutSeconds(owner.config.getAskTimeoutSeconds())
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
                                owner.appendToolMessage(messages, tc, answer);
                                return owner.toolResultEvent(tc, answer);
                            })
                            .onErrorResume(e -> {
                                log.warn("ask_user 等待回答异常: {}", e.getMessage());
                                String output = "（提问通道异常，请基于现有信息继续任务）";
                                owner.appendToolMessage(messages, tc, output);
                                return reactor.core.publisher.Mono.just(owner.toolResultEvent(tc, output));
                            })
                            .flux());
        } catch (Exception e) {
            log.warn("ask_user 解析失败: {}", e.getMessage());
            String output = "提问解析失败: " + e.getMessage();
            owner.appendToolMessage(messages, tc, output);
            return Flux.just(owner.toolResultEvent(tc, output));
        }
    }

    // ==================== 子代理派生（spawn_subagents） ====================

    /** 单个子任务定义 */
    record SubAgentSpec(String name, String agentId, String prompt) {}

    /** 单个子代理执行结果 */
    record SubAgentResult(int index, String name, String agentId, boolean success,
                                  String output, long tokens, long durationMs) {}

    /** spawn 参数解析结果：error 非空表示校验失败 */
    static final class SpawnRequest {
        final List<SubAgentSpec> agents = new ArrayList<>();
        String error;
    }

    /** 解析并校验 spawn_subagents 参数（数量/提示词长度上限，防御性拦截超规格派生） */
    SpawnRequest parseSpawnRequest(String arguments) {
        SpawnRequest req = new SpawnRequest();
        try {
            JsonNode root = objectMapper.readTree(arguments != null ? arguments : "{}");
            JsonNode agentsNode = root.path("agents");
            if (!agentsNode.isArray() || agentsNode.isEmpty()) {
                req.error = "缺少 agents 数组参数";
                return req;
            }
            AgentEngineConfig.Subagents sub = owner.config.getSubagents();
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
    AgentTask buildSubAgentTask(SubAgentSpec spec, ToolContext ctx) {
        AgentEngineConfig.Subagents sub = owner.config.getSubagents();
        String subAgentId = (spec.agentId() != null && !spec.agentId().isBlank())
                ? spec.agentId() : ctx.getAgentId();
        // 子任务模型解析：子代理自身 AgentSpec 配置了模型则优先（task 级不设显式模型）；
        // 否则回退父任务已解析的 provider/model，保证子任务不会 MODEL_NOT_RESOLVED
        AgentSpec subSpec = owner.loadAgent(subAgentId);
        boolean specHasModel = subSpec != null && subSpec.getModelProvider() != null
                && subSpec.getModelName() != null;
        boolean shareHistory = sub != null && sub.isShareSessionHistory();
        boolean shareWorkspace = sub == null || sub.isShareWorkspace();
        return AgentTask.builder()
                .agentId(subAgentId)
                .sessionId(shareHistory ? ctx.getSessionId() : null)
                // 工作空间绑定与历史共享解耦：默认共享父会话工作空间（文件工具可读写、
                // 写入归属父会话），历史是否共享由 shareSessionHistory 独立控制
                .workspaceSessionId(shareWorkspace ? ctx.getSessionId() : null)
                .userId(ctx.getUserId())
                .message(spec.prompt())
                .agentDepth(ctx.getAgentDepth() + 1)
                .modelProvider(specHasModel ? null : ctx.getModelProvider())
                .modelName(specHasModel ? null : ctx.getModelName())
                .quotaTokenBudget(ctx.getQuotaTokenBudget())
                .quotaBlockEnabled(ctx.getQuotaBlockEnabled())
                // 工作区继承与历史共享解耦：子代理文件工具必须指向父会话工作区
                //（shareSessionHistory=false 时 sessionId 置空，若不同步透传会回落全局默认——子代理读错工作区实测）
                .workspaceRoot(ctx.getWorkspaceRoot())
                // 入口调度指令继承父任务裁决产物（不重复解析，设计文档 56 §5.3）
                .entryDirectives(ctx.getEntryDirectives())
                .entryDirectivesSource(ctx.getEntryDirectivesSource())
                .entryDirectivesMeta(ctx.getEntryDirectivesMeta())
                .build();
    }

    /** 提交一批子代理并行执行：独立线程池（防嵌套 join 死锁）+ 单分支超时/异常降级为错误文本 */
    List<CompletableFuture<SubAgentResult>> submitSubAgents(SpawnRequest request, ToolContext ctx) {
        AgentEngineConfig.Subagents sub = owner.config.getSubagents();
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
                        LlmResponse resp = owner.execute(subTask);
                        long tokens = resp.getUsage() != null && resp.getUsage().getTotalTokens() != null
                                ? resp.getUsage().getTotalTokens()
                                : Math.max(1, (resp.getContent() == null ? 0 : resp.getContent().length()) / 3);
                        return new SubAgentResult(idx, spec.name(), subTask.getAgentId(), true,
                                resp.getContent(), tokens, System.currentTimeMillis() - start);
                    }, owner.config.getSubAgentExecutor())
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
    java.util.AbstractMap.SimpleEntry<String, Boolean> aggregateSpawnResults(
            ToolContext ctx, BudgetContext parentBudget,
            List<CompletableFuture<SubAgentResult>> futures, long startMs) {
        List<SubAgentResult> results = futures.stream().map(CompletableFuture::join).toList();
        return aggregateResults(ctx, parentBudget, results, startMs);
    }

    /** 聚合结果（流式路径复用）：从分支结果列表直接聚合（不再经 Future join） */
    java.util.AbstractMap.SimpleEntry<String, Boolean> aggregateResults(
            ToolContext ctx, BudgetContext parentBudget,
            List<SubAgentResult> results, long startMs) {
        AgentEngineConfig.Subagents sub = owner.config.getSubagents();
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
            sb.append("\n").append(owner.truncate(r.output(), maxOutputChars));
        }
        if (totalTokens > 0 && parentBudget != null) {
            owner.budgetController.consume(parentBudget, totalTokens,
                    owner.budgetController.calculateCost(ctx.getModelName(), totalTokens / 2, totalTokens - totalTokens / 2));
        }
        owner.recordMetricSafe("agent.subagents.spawn", results.size(),
                Map.of("agentId", ctx.getAgentId() != null ? ctx.getAgentId() : "unknown",
                        "failed", String.valueOf(failed)));
        return new java.util.AbstractMap.SimpleEntry<>(
                owner.outputSanitizer.checkOutput(sb.toString()), failed == results.size());
    }

    /** 深度护栏（运行时兜底）：即使模型幻觉调用未注册的 spawn 工具，也拒绝超深度派生 */
    String depthGuardMessage(ToolContext toolContext) {
        AgentEngineConfig.Subagents sub = owner.config.getSubagents();
        int maxDepth = sub != null ? sub.getMaxDepth() : 2;
        if (toolContext.getAgentDepth() + 1 > maxDepth) {
            return "已达子代理嵌套深度上限（" + maxDepth + "），请直接基于现有信息完成任务";
        }
        return null;
    }

    /** 同步路径 spawn：并行派发、等待全部完成、聚合为单个工具结果 */
    ToolResult executeSpawnAgentsSync(ToolCall tc, ToolContext toolContext, BudgetContext parentBudget) {
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
    Flux<AgentEvent> executeSpawnAgentsStream(ToolCall tc, ToolContext toolContext,
                                                      BudgetContext parentBudget, List<Message> messages) {
        String depthError = depthGuardMessage(toolContext);
        if (depthError != null) {
            owner.appendToolMessage(messages, tc, depthError);
            return Flux.just(owner.toolResultEvent(tc, depthError));
        }
        SpawnRequest request = parseSpawnRequest(tc.getArguments());
        if (request.error != null) {
            owner.appendToolMessage(messages, tc, request.error);
            return Flux.just(owner.toolResultEvent(tc, request.error));
        }
        return Flux.defer(() -> {
            long start = System.currentTimeMillis();
            AgentEngineConfig.Subagents sub = owner.config.getSubagents();
            int timeoutSeconds = sub != null ? sub.getTimeoutSeconds() : 180;
            boolean redeployOnce = sub != null && sub.isRedeployFailedOnce();
            java.util.List<SubAgentResult> allResults = new java.util.concurrent.CopyOnWriteArrayList<>();

            Flux<AgentEvent> waves = executeSubAgentWave(request.agents, toolContext, start, timeoutSeconds, allResults, 1);
            // P2 失败自动重派（docs/design/43）：有失败分支时自动重跑一次，
            // 防限流/瞬态导致整波成果全损；可经 agent.engine.subagents.redeploy-failed-once=false 关闭
            if (redeployOnce) {
                waves = waves.concatWith(Flux.defer(() -> {
                    List<SubAgentSpec> failedSpecs = allResults.stream()
                            .filter(r -> !r.success())
                            .map(r -> request.agents.get(Math.min(Math.max(r.index() - 1, 0), request.agents.size() - 1)))
                            .toList();
                    if (failedSpecs.isEmpty()) {
                        return Flux.empty();
                    }
                    log.info("子代理失败自动重派: {}/{} 个分支将重试一次", failedSpecs.size(), request.agents.size());
                    return executeSubAgentWave(failedSpecs, toolContext, start, timeoutSeconds, allResults, 2);
                }));
            }
            // 终局聚合：全部波次完成后子消耗折算入父预算，回灌 tool 消息并发 tool_result
            return Flux.concat(waves, Mono.fromCallable(() -> {
                        var aggregation = aggregateResults(toolContext, parentBudget, allResults, start);
                        owner.appendToolMessage(messages, tc, aggregation.getKey());
                        return owner.toolResultEvent(tc, aggregation.getKey());
                    }).flux());
        });
    }

    /**
     * 构建一波子代理分支（P2 重派复用）：开始事件 + 并行分支（含心跳维持/失败隔离）+ 批次统计。
     * 结果写入 allResults（跨波累计）；批次内不做终局聚合/预算折算，由调用方在全部波次后统一执行。
     */
    Flux<AgentEvent> executeSubAgentWave(List<SubAgentSpec> specs, ToolContext toolContext,
                                                 long start, int timeoutSeconds,
                                                 java.util.List<SubAgentResult> allResults, int waveNo) {
        List<AgentEvent> startEvents = new ArrayList<>();
        List<Flux<AgentEvent>> branchFluxes = new ArrayList<>();
        List<BranchRun> runs = new ArrayList<>();
        int index = 0;
        for (SubAgentSpec spec : specs) {
            final int idx = index++;
            final String subagentId = "sub-" + waveNo + "-" + idx + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            final long branchStart = System.currentTimeMillis();
            final AgentTask subTask = buildSubAgentTask(spec, toolContext);
            // 分支现场：正文累积 / token（done 事件）/ 失败标记 / 结束时间；聚合时定格为 SubAgentResult
            final BranchRun run = new BranchRun(idx, spec.name(), subTask.getAgentId(), branchStart);
            // 派生开始事件：前端时间线立即出现"子智能体 … 执行中"行
            startEvents.add(subagentStatusEvent(run.toResult(), subagentId, "running", null));
            final java.util.concurrent.CompletableFuture<Void> branchDone = new java.util.concurrent.CompletableFuture<>();
            Flux<AgentEvent> branchBase = owner.executeStream(subTask)
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
                    .doFinally(sig -> branchDone.complete(null));
            // P1 心跳（docs/design/43）：子代理内部长工具执行（如 mvn build >180s）期间
            // 无事件输出，分支 timeout（事件间不活动超时）会把"正在干活"的分支误杀——
            // 合并 60s 心跳维持活跃度，前端对未知事件类型自动忽略
            Flux<AgentEvent> heartbeat = Flux.interval(java.time.Duration.ofSeconds(60))
                    .map(i -> withSubagentTag(AgentEvent.builder()
                            .type("tool_heartbeat").content("子任务执行中…")
                            .build(), subagentId, idx, spec.name()))
                    .takeUntilOther(Mono.fromFuture(branchDone));
            Flux<AgentEvent> branch = branchBase.mergeWith(heartbeat)
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .onErrorResume(e -> {
                        run.failed = true;
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        String reason = cause instanceof java.util.concurrent.TimeoutException
                                ? "执行超时（" + timeoutSeconds + " 秒内无任何输出，可调整 agent.engine.subagents.timeout-seconds）"
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
        // 批次统计：结果写入 allResults（P2 重派判定数据源），不在此终局
        Flux<AgentEvent> stats = Mono.fromCallable(() -> {
            List<SubAgentResult> results = runs.stream().map(BranchRun::toResult).toList();
            allResults.addAll(results);
            long failed = results.stream().filter(r -> !r.success()).count();
            return AgentEvent.builder().type(AgentEvent.STATUS)
                    .content("子智能体批次 " + waveNo + " 完成：共 " + results.size() + "，失败 " + failed)
                    .build();
        }).flux();
        return Flux.concat(Flux.fromIterable(startEvents), merged, stats);
    }

    /** 分支流式执行现场（结果在聚合时定格为 SubAgentResult） */
    static final class BranchRun {
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
    AgentEvent withSubagentTag(AgentEvent ev, String subagentId, int index, String name) {
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

    AgentEvent subagentStatusEvent(SubAgentResult r, String subagentId, String statusOverride, String resultOverride) {
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
}
