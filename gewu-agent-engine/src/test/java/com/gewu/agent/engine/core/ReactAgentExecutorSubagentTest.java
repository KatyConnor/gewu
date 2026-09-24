package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.budget.BudgetContext;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.cognition.ComplexityRouter;
import com.gewu.agent.engine.cognition.DualSystemRouter;
import com.gewu.agent.engine.cognition.NoOpPerceptionEngine;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.LlmRequestBodyBuilder;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.memory.NoOpMemoryRouter;
import com.gewu.agent.engine.memory.NoOpMemoryStore;
import com.gewu.agent.engine.message.DefaultMessageBuilder;
import com.gewu.agent.engine.message.SystemPromptComposer;
import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.agent.engine.spi.TraceService;
import com.gewu.agent.engine.spi.defaults.NoOpPersistenceService;
import com.gewu.agent.engine.spi.defaults.NoOpSessionContextService;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.agent.engine.tool.security.OutputSanitizer;
import com.gewu.agent.engine.tool.security.PromptInjectionDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReactAgentExecutor} 子代理派生（spawn_subagents 内置工具）单元测试。
 * <p>覆盖：并行派生与聚合 / 深度护栏（注册与防递归）/ 单分支失败不拖垮整体 /
 * 数量超限拦截 / 父预算折算子消耗 / 流式进度事件与聚合结果。
 */
@DisplayName("ReAct 执行器子代理派生（spawn_subagents）")
class ReactAgentExecutorSubagentTest {

    /**
     * 按请求内容路由的 LLM 客户端：父请求与并行子请求的到达顺序不确定，
     * 以「是否携带 tool 消息 + 子任务提示词特征」路由，而不是按调用次序脚本化。
     */
    static class RoutingLlmClient implements LlmClient {
        final List<LlmRequest> chatRequests = new CopyOnWriteArrayList<>();
        final List<LlmRequest> streamRequests = new CopyOnWriteArrayList<>();
        final Function<LlmRequest, LlmResponse> syncRouter;
        final Function<LlmRequest, Flux<LlmChunk>> streamRouter;

        RoutingLlmClient(Function<LlmRequest, LlmResponse> syncRouter,
                         Function<LlmRequest, Flux<LlmChunk>> streamRouter) {
            this.syncRouter = syncRouter;
            this.streamRouter = streamRouter;
        }

        @Override
        public String getProvider() {
            return "test";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            chatRequests.add(request);
            return syncRouter.apply(request);
        }

        @Override
        public Flux<LlmChunk> chatStream(LlmRequest request) {
            streamRequests.add(request);
            return streamRouter.apply(request);
        }

        /** 请求是否已处于工具结果回灌轮（父任务第二轮/聚合轮） */
        static boolean hasToolMessage(LlmRequest r) {
            return r.getMessages().stream().anyMatch(m -> "tool".equals(m.getRole()));
        }

        /** 最后一条 user 消息文本（子任务 prompt 即 user 消息） */
        static String lastUserText(LlmRequest r) {
            for (int i = r.getMessages().size() - 1; i >= 0; i--) {
                Message m = r.getMessages().get(i);
                if ("user".equals(m.getRole())) {
                    return m.getContent();
                }
            }
            return "";
        }
    }

    /** 捕获创建的预算上下文（供断言父预算折算子消耗） */
    static class CapturingBudgetController extends BudgetController {
        final List<BudgetContext> created = new CopyOnWriteArrayList<>();

        CapturingBudgetController() {
            super(81920, 300000, 10);
        }

        @Override
        public BudgetContext createBudget(String taskLevel) {
            BudgetContext ctx = super.createBudget(taskLevel);
            created.add(ctx);
            return ctx;
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutorService toolPool;
    private ExecutorService subAgentPool;
    private ToolExecutor toolExecutor;
    private CapturingBudgetController budget;

    @BeforeEach
    void setUp() {
        toolPool = Executors.newFixedThreadPool(4);
        subAgentPool = Executors.newFixedThreadPool(4);
        toolExecutor = mock(ToolExecutor.class);
        budget = new CapturingBudgetController();
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("工具结果").build());
    }

    @AfterEach
    void tearDown() {
        toolPool.shutdownNow();
        subAgentPool.shutdownNow();
    }

    private ReactAgentExecutor executor(RoutingLlmClient client, int maxPerSpawn, int maxDepth, int timeoutSeconds) {
        return new ReactAgentExecutor(
                new LlmClientRegistry(List.of(client), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(10)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolPool)
                        .defaultHistoryLimit(50)
                        .subAgentExecutor(subAgentPool)
                        .subagents(AgentEngineConfig.Subagents.builder()
                                .enabled(true)
                                .maxPerSpawn(maxPerSpawn)
                                .maxDepth(maxDepth)
                                .timeoutSeconds(timeoutSeconds)
                                .build())
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                budget,
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTrace(),
                (name, value, tags) -> {
                },
                new ResponseCache() {
                    @Override
                    public String get(String prompt, String contextKey) {
                        return null;
                    }

                    @Override
                    public void put(String prompt, String response, String contextKey) {
                    }
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                (taskDescription, complexity, privacyLevel, latencyPreference, budgetRemaining) -> null,
                null,
                null,
                null,
                null);
    }

    private AgentTask task() {
        return AgentTask.builder()
                .sessionId(null)
                .userId("user-1")
                .message("请并行调研以下两个主题后汇总")
                .modelProvider("test")
                .modelName("test-model")
                .build();
    }

    private static LlmResponse text(String content) {
        return LlmResponse.builder().content(content).build();
    }

    private static LlmResponse textWithUsage(String content, long totalTokens) {
        return LlmResponse.builder()
                .content(content)
                .usage(LlmResponse.Usage.builder().totalTokens((int) totalTokens).build())
                .build();
    }

    private static LlmResponse spawnCall(String id, String arguments) {
        return LlmResponse.builder()
                .toolCalls(List.of(ToolCall.builder().id(id).name("spawn_subagents").arguments(arguments).build()))
                .build();
    }

    private TraceService noOpTrace() {
        return new TraceService() {
            @Override
            public void recordTrace(String executionId, String nodeId, String phase,
                                    String action, String detail) {
            }
        };
    }

    @Test
    @DisplayName("并行派生：两个子代理并行执行，聚合结果回灌主循环")
    void syncSpawnAggregatesParallelResults() {
        RoutingLlmClient client = new RoutingLlmClient(r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                return text("汇总完成");
            }
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("调研A")) {
                return textWithUsage("A的结论", 120);
            }
            if (user.contains("调研B")) {
                return textWithUsage("B的结论", 80);
            }
            return spawnCall("spawn-1", """
                    {"agents":[{"name":"主题A","prompt":"调研A：API 能力"},
                               {"name":"主题B","prompt":"调研B：依赖现状"}]}
                    """);
        }, r -> Flux.error(new UnsupportedOperationException("本用例走同步路径")));

        LlmResponse response = executor(client, 5, 2, 60).execute(task());

        assertThat(response.getContent()).isEqualTo("汇总完成");
        // 父任务 2 轮 + 子代理 2 次执行
        assertThat(client.chatRequests).hasSize(4);
        // 子代理并行：第二轮请求包含聚合 tool 消息，两个分支结果齐备
        Message toolMsg = client.chatRequests.get(3).getMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertThat(toolMsg.getContent())
                .contains("成功 2").contains("## 子代理[0]").contains("A的结论")
                .contains("## 子代理[1]").contains("B的结论");
        // 子任务消耗折算入父预算（120+80=200）
        assertThat(budget.created).isNotEmpty();
        assertThat(budget.created.get(0).getTokenConsumed()).isGreaterThanOrEqualTo(200);
        // 子任务会话隔离：子请求不携带父 sessionId（默认隔离）
        for (LlmRequest subReq : client.chatRequests.subList(1, 3)) {
            assertThat(RoutingLlmClient.lastUserText(subReq)).doesNotContain("请并行调研");
        }
    }

    @Test
    @DisplayName("深度护栏：depth=1 注册派生工具，depth>=maxDepth 不再注册（防递归）")
    void depthGateOnToolRegistration() {
        RoutingLlmClient client = new RoutingLlmClient(r -> text("直答"),
                r -> Flux.error(new UnsupportedOperationException()));
        ReactAgentExecutor executor = executor(client, 5, 2, 60);

        // depth=1（子代理层）：仍可派生
        executor.execute(AgentTask.builder()
                .userId("user-1").message("子代理层的任务")
                .modelProvider("test").modelName("test-model")
                .agentDepth(1).build());
        assertThat(client.chatRequests.get(0).getTools())
                .extracting(com.gewu.agent.engine.llm.model.ToolDefinition::getName)
                .contains("spawn_subagents");

        // depth=2（达到 maxDepth）：不再注册
        executor.execute(AgentTask.builder()
                .userId("user-1").message("更深层的任务")
                .modelProvider("test").modelName("test-model")
                .agentDepth(2).build());
        assertThat(client.chatRequests.get(1).getTools())
                .extracting(com.gewu.agent.engine.llm.model.ToolDefinition::getName)
                .doesNotContain("spawn_subagents");
    }

    @Test
    @DisplayName("单分支失败不拖垮整体：A 分支异常降级为错误文本，B 分支正常聚合")
    void subagentFailureDegradesGracefully() {
        RoutingLlmClient client = new RoutingLlmClient(r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                return text("基于部分结果继续");
            }
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("调研A")) {
                throw new RuntimeException("LLM 连接失败");
            }
            if (user.contains("调研B")) {
                return text("B的结论");
            }
            return spawnCall("spawn-1", """
                    {"agents":[{"name":"主题A","prompt":"调研A：故障场景"},
                               {"name":"主题B","prompt":"调研B：正常场景"}]}
                    """);
        }, r -> Flux.error(new UnsupportedOperationException()));

        LlmResponse response = executor(client, 5, 2, 60).execute(task());

        assertThat(response.getContent()).isEqualTo("基于部分结果继续");
        Message toolMsg = client.chatRequests.get(3).getMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertThat(toolMsg.getContent())
                .contains("失败 1").contains("成功 1")
                .contains("B的结论").contains("LLM 连接失败");
    }

    @Test
    @DisplayName("单分支超时降级：超时分支标记失败，整体继续")
    void subagentTimeoutDegradesGracefully() {
        RoutingLlmClient client = new RoutingLlmClient(r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                return text("超时后继续");
            }
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("调研A")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return text("不应出现");
            }
            if (user.contains("调研B")) {
                return text("B的结论");
            }
            return spawnCall("spawn-1", """
                    {"agents":[{"name":"慢任务","prompt":"调研A：慢分支"},
                               {"name":"快任务","prompt":"调研B：快分支"}]}
                    """);
        }, r -> Flux.error(new UnsupportedOperationException()));

        LlmResponse response = executor(client, 5, 2, 1).execute(task());

        assertThat(response.getContent()).isEqualTo("超时后继续");
        Message toolMsg = client.chatRequests.get(3).getMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertThat(toolMsg.getContent())
                .contains("执行超时").contains("B的结论").doesNotContain("不应出现");
    }

    @Test
    @DisplayName("数量超限拦截：超过 maxPerSpawn 直接返回错误，不派发子任务")
    void maxPerSpawnRejected() {
        RoutingLlmClient client = new RoutingLlmClient(r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                return text("已收到超限错误");
            }
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("分派任务")) {
                return text("不应派发");
            }
            return spawnCall("spawn-1", """
                    {"agents":[{"name":"1","prompt":"分派任务一"},
                               {"name":"2","prompt":"分派任务二"},
                               {"name":"3","prompt":"分派任务三"}]}
                    """);
        }, r -> Flux.error(new UnsupportedOperationException()));

        LlmResponse response = executor(client, 2, 2, 60).execute(task());

        assertThat(response.getContent()).isEqualTo("已收到超限错误");
        // 父任务 2 轮，无任何子代理调用（子任务提示词未被路由到）
        assertThat(client.chatRequests).hasSize(2);
        Message toolMsg = client.chatRequests.get(1).getMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow();
        assertThat(toolMsg.getContent()).contains("子代理数量超限");
    }

    @Test
    @DisplayName("流式路径：subagent_status 进度事件 + 聚合 tool_result + 主循环继续")
    void streamSpawnEmitsProgressAndAggregatedResult() {
        // 注意：子代理内部始终走同步 execute()（client.chat），父任务走 chatStream——
        // 因此流式用例的 syncRouter 必须能路由子任务请求
        RoutingLlmClient client = new RoutingLlmClient(r -> {
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("调研A")) {
                return textWithUsage("A的结论", 120);
            }
            if (user.contains("调研B")) {
                return textWithUsage("B的结论", 80);
            }
            throw new UnsupportedOperationException("父任务不应走同步路径");
        }, r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                return Flux.just(
                        LlmChunk.builder().delta("流式汇总完成").build(),
                        LlmChunk.builder().finishReason("stop").build());
            }
            String user = RoutingLlmClient.lastUserText(r);
            if (user.contains("调研A")) {
                return Flux.just(
                        LlmChunk.builder().delta("A的结论").build(),
                        LlmChunk.builder().finishReason("stop").build());
            }
            if (user.contains("调研B")) {
                return Flux.just(
                        LlmChunk.builder().delta("B的结论").build(),
                        LlmChunk.builder().finishReason("stop").build());
            }
            return Flux.just(
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id("spawn-1").name("spawn_subagents")
                            .arguments("{\"agents\":[{\"name\":\"主题A\",\"prompt\":\"调研A：流式\"},{\"name\":\"主题B\",\"prompt\":\"调研B：流式\"}]}")
                            .build()).build(),
                    LlmChunk.builder().finishReason("tool_calls").build());
        });

        List<AgentEvent> events = executor(client, 5, 2, 60)
                .executeStream(task())
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(events).isNotNull();
        // 两个分支的进度事件：开始(running)×2 + 完成(success)×2（二期流式语义）
        List<AgentEvent> statusEvents = events.stream()
                .filter(e -> AgentEvent.SUBAGENT_STATUS.equals(e.getType()))
                .toList();
        assertThat(statusEvents).hasSize(4);
        assertThat(statusEvents.stream()
                .filter(e -> "running".equals(e.getMetadata().get("status")))).hasSize(2);
        List<AgentEvent> doneStatus = statusEvents.stream()
                .filter(e -> "success".equals(e.getMetadata().get("status"))).toList();
        assertThat(doneStatus).hasSize(2);
        assertThat(doneStatus).allSatisfy(e ->
                assertThat(e.getMetadata())
                        .containsEntry("status", "success")
                        .containsKey("subagentId")
                        .containsKey("name").containsKey("durationMs").containsKey("tokens")
                        .containsKey("result"));
        // 分支内部事件带 subagentId 标签（前端按其分桶流式渲染右侧面板）
        assertThat(events.stream()
                .filter(e -> e.getMetadata() != null && e.getMetadata().containsKey("subagentId")
                        && !AgentEvent.SUBAGENT_STATUS.equals(e.getType()))
                .count()).isGreaterThanOrEqualTo(1);
        // 聚合 tool_result 携带两分支结果
        String aggregated = events.stream()
                .filter(e -> AgentEvent.TOOL_RESULT.equals(e.getType()))
                .map(e -> e.getToolResult().getResult())
                .findFirst().orElseThrow();
        assertThat(aggregated).contains("A的结论").contains("B的结论").contains("成功 2");
        // 主循环继续：最终 DONE
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.DONE);
        });
        // 父任务第二轮请求回灌了聚合 tool 消息
        assertThat(client.streamRequests).anySatisfy(r -> {
            if (RoutingLlmClient.hasToolMessage(r)) {
                assertThat(r.getMessages().stream()
                        .filter(m -> "tool".equals(m.getRole())).findFirst().orElseThrow()
                        .getContent()).contains("A的结论");
            }
        });
    }

    @Test
    @DisplayName("工具定义注册：depth=0 的顶层任务 schema 含数量上限")
    void topLevelTaskRegistersSpawnTool() {
        RoutingLlmClient client = new RoutingLlmClient(r -> text("直答"),
                r -> Flux.error(new UnsupportedOperationException()));

        executor(client, 3, 2, 60).execute(task());

        com.gewu.agent.engine.llm.model.ToolDefinition spawnDef =
                client.chatRequests.get(0).getTools().stream()
                        .filter(t -> "spawn_subagents".equals(t.getName()))
                        .findFirst().orElseThrow();
        assertThat(spawnDef.getParameters()).contains("\"maxItems\":3");
    }
}
