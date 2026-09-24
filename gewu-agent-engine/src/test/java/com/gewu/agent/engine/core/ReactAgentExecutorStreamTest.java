package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.memory.NoOpMemoryRouter;
import com.gewu.agent.engine.memory.NoOpMemoryStore;
import com.gewu.agent.engine.message.DefaultMessageBuilder;
import com.gewu.agent.engine.message.SystemPromptComposer;
import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.agent.engine.spi.TraceService;
import com.gewu.agent.engine.spi.MetricService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReactAgentExecutor} 流式路径单元测试（T1.2 协议修复验证）。
 * <p>验证三个行为：任务级 maxTokens 透传到流式请求、多轮工具对话的
 * assistant tool_calls 声明回灌进下一轮请求体、基础事件序列。
 */
@DisplayName("ReAct 执行器流式路径")
class ReactAgentExecutorStreamTest {

    /** 脚本化 LLM 客户端：按调用次数返回预设分块流，并记录收到的请求 */
    static class FixedLlmClient implements LlmClient {
        final List<LlmRequest> recordedRequests = new CopyOnWriteArrayList<>();
        final List<Flux<LlmChunk>> scriptedRounds = new CopyOnWriteArrayList<>();

        @Override
        public String getProvider() {
            return "test";
        }

        @Override
        public com.gewu.agent.engine.llm.model.LlmResponse chat(LlmRequest request) {
            throw new UnsupportedOperationException("本测试仅覆盖流式路径");
        }

        @Override
        public Flux<LlmChunk> chatStream(LlmRequest request) {
            recordedRequests.add(request);
            int index = recordedRequests.size() - 1;
            return scriptedRounds.get(index);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutorService toolExecutorPool;
    private ToolExecutor toolExecutor;
    private FixedLlmClient llmClient;
    private ReactAgentExecutor executor;

    @BeforeEach
    void setUp() {
        toolExecutorPool = Executors.newFixedThreadPool(2);
        toolExecutor = mock(ToolExecutor.class);
        llmClient = new FixedLlmClient();

        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(3)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 10),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                noOpMetricService(),
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);
    }

    @AfterEach
    void tearDown() {
        toolExecutorPool.shutdownNow();
    }

    private AgentTask task(int maxTokens) {
        return AgentTask.builder()
                .sessionId("sess-1")
                .userId("user-1")
                .message("北京今天天气怎么样")
                .modelProvider("test")
                .modelName("test-model")
                .maxTokens(maxTokens)
                .build();
    }

    @Test
    @DisplayName("任务级 maxTokens 透传到流式 LLM 请求（原缺陷：流式恒用默认值）")
    void streamTaskMaxTokensRespected() {
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("晴").build(),
                LlmChunk.builder().delta("，25度").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(1234))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(llmClient.recordedRequests).hasSize(1);
        assertThat(llmClient.recordedRequests.get(0).getMaxTokens()).isEqualTo(1234);
    }

    @Test
    @DisplayName("多轮工具对话：assistant tool_calls 声明与 tool 结果回灌进下一轮请求")
    void streamAssistantToolCallsSerializedInNextRequest() throws Exception {
        // 第一轮：LLM 请求调用 query_weather 工具（流式增量累积）
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().reasoning("需要查天气").build(),
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-1").name("query_weather").build()).build(),
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .arguments("{\"city\":\"北京\"}").build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        // 第二轮：拿到工具结果后给出最终回答
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("北京今天晴，25度").build(),
                LlmChunk.builder().finishReason("stop").build()));

        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴，25度").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        // 事件序列：thinking -> tool_call -> tool_executing -> tool_result -> content -> done
        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        assertThat(types).containsSubsequence("thinking", "tool_call", "tool_executing",
                "tool_result", "content", "done");

        // 两轮 LLM 调用
        assertThat(llmClient.recordedRequests).hasSize(2);

        // 第二轮请求的消息中：assistant 消息携带 toolCalls 声明，tool 消息关联 toolCallId
        List<Message> secondRoundMessages = llmClient.recordedRequests.get(1).getMessages();
        Message assistant = secondRoundMessages.stream()
                .filter(m -> "assistant".equals(m.getRole()) && m.getToolCalls() != null)
                .findFirst().orElseThrow(() -> new AssertionError("assistant 消息缺少 toolCalls 声明"));
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().get(0).getId()).isEqualTo("call-1");
        assertThat(assistant.getToolCalls().get(0).getName()).isEqualTo("query_weather");
        assertThat(assistant.getToolCalls().get(0).getArguments())
                .isEqualTo("{\"city\":\"北京\"}");

        Message toolMsg = secondRoundMessages.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少 tool 消息"));
        assertThat(toolMsg.getToolCallId()).isEqualTo("call-1");
        assertThat(toolMsg.getName()).isEqualTo("query_weather");

        // 端到端协议验证：按 OpenAI 规范序列化后请求体包含 tool_calls 声明
        JsonNode messagesJson = new LlmRequestBodyBuilder(objectMapper)
                .buildMessagesArray(new ArrayList<>(secondRoundMessages));
        String json = messagesJson.toString();
        assertThat(json).contains("\"tool_calls\"");
        assertThat(json).contains("\"tool_call_id\":\"call-1\"");
        assertThat(json).contains("\"type\":\"function\"");
    }

    @Test
    @DisplayName("直答流事件序列：status -> content* -> done")
    void streamDirectAnswerEventSequence() {
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("你好").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(4096))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(0).getType()).isEqualTo("status");
        assertThat(events.get(events.size() - 1).getType()).isEqualTo("done");
        assertThat(events.stream().filter(e -> "content".equals(e.getType()))).hasSize(1);
        // done 事件透传 finish_reason（S9：前端区分正常结束/截断）
        assertThat(events.get(events.size() - 1).getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("正文非空中途截断：CONTENT_RESET 清空 + 加倍 max_tokens 重新生成（S9）")
    void streamMidContentTruncationRetriesWithReset() {
        // 第一轮：思考完输出部分正文后被 length 掐断（用户实测缺陷形态）
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().reasoning("先写脚本").build(),
                LlmChunk.builder().delta("#!/usr/bin/env bash").build(),
                LlmChunk.builder().finishReason("length").build()));
        // 第二轮：加倍预算后完整生成
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("完整回复").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(1024))
                .collectList().block();

        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        // 事件链：半截 content -> content_reset -> status -> content -> done
        assertThat(types).containsSubsequence("content_reset", "status", "content", "done");
        // 两次 LLM 调用，第二次 max_tokens 加倍
        assertThat(llmClient.recordedRequests).hasSize(2);
        assertThat(llmClient.recordedRequests.get(1).getMaxTokens()).isEqualTo(2048);
        // 重试成功后 done 为正常结束
        assertThat(events.get(events.size() - 1).getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("正文为空截断：不加倍提示直接重试，保持 S8 行为")
    void streamBlankTruncationRetriesWithoutReset() {
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().reasoning("思考中").build(),
                LlmChunk.builder().finishReason("length").build()));
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("完整回复").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(1024))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(llmClient.recordedRequests).hasSize(2);
        assertThat(events.stream().map(AgentEvent::getType)).doesNotContain("content_reset");
        assertThat(llmClient.recordedRequests.get(1).getMaxTokens()).isEqualTo(2048);
    }

    @Test
    @DisplayName("重试耗尽且正文非空：保留部分内容，STATUS 明示 + done 带 finishReason=length")
    void streamRetriesExhaustedKeepsPartialContent() {
        // 四轮（初次 + 3 次重试）全部 length 且正文非空
        for (int i = 0; i < 4; i++) {
            llmClient.scriptedRounds.add(Flux.just(
                    LlmChunk.builder().delta("部分内容" + i).build(),
                    LlmChunk.builder().finishReason("length").build()));
        }

        List<AgentEvent> events = executor.executeStream(task(1024))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(llmClient.recordedRequests).hasSize(4);
        // 每次非空截断重试各发一次 content_reset
        assertThat(events.stream().filter(e -> "content_reset".equals(e.getType()))).hasSize(3);
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo("done");
        assertThat(last.getFinishReason()).isEqualTo("length");
        // 不静默：截断保留路径包含 STATUS 提示（而非直接 done）
        assertThat(events.stream().map(AgentEvent::getType)).doesNotContain("error");
    }

    @Test
    @DisplayName("会话任务的预算下限：L1 提升为 L2，无 session 保持 L1（用户实报 199s/30s 熔断）")
    void sessionTaskBudgetFloorPromotesL1ToL2() {
        List<String> levels = new java.util.concurrent.CopyOnWriteArrayList<>();
        BudgetController recording = new BudgetController(81920, 300000, 10) {
            @Override
            public com.gewu.agent.engine.budget.BudgetContext createBudget(String taskLevel) {
                levels.add(taskLevel);
                return super.createBudget(taskLevel);
            }
        };
        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(3)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                recording,
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                noOpMetricService(),
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // 带会话的短消息任务：路由判 L1，应提升为 L2
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("ok").build(),
                LlmChunk.builder().finishReason("stop").build()));
        executor.executeStream(task(1024)).collectList().block();
        assertThat(levels).containsExactly("L2");

        // 无 session 的裸调用：保持 L1
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("ok").build(),
                LlmChunk.builder().finishReason("stop").build()));
        AgentTask noSession = AgentTask.builder()
                .userId("user-1")
                .message("北京今天天气怎么样")
                .modelProvider("test")
                .modelName("test-model")
                .build();
        executor.executeStream(noSession).collectList().block();
        assertThat(levels).containsExactly("L2", "L1");
    }

    @Test
    @DisplayName("内置 plan_task 工具：计划事件透出 + done 携带快照（S9 F5）")
    void planToolEmitsPlanEvents() {
        // 第一轮：模型调用内置 plan_task 制定任务清单
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-1").name("plan_task").build()).build(),
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .arguments("{\"title\":\"编译修复\",\"steps\":[{\"id\":\"1\",\"text\":\"分析错误\",\"status\":\"in_progress\"},{\"id\":\"2\",\"text\":\"修复\",\"status\":\"pending\"}]}")
                        .build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        // 第二轮：按计划执行后给出最终回答
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("已按计划执行").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        // 计划事件在工具结果之前发出（模型提交计划即更新卡片，不等外部执行）
        assertThat(types).containsSubsequence("tool_call", "tool_executing",
                "plan_created", "tool_result", "content", "done");
        AgentEvent planEvent = events.stream()
                .filter(e -> "plan_created".equals(e.getType())).findFirst().orElseThrow();
        assertThat(planEvent.getPlanTitle()).isEqualTo("编译修复");
        assertThat(planEvent.getPlan()).hasSize(2);
        assertThat(planEvent.getPlan().get(0).getStatus()).isEqualTo("in_progress");
        assertThat(planEvent.getPlan().get(1).getText()).isEqualTo("修复");
        // done 携带最终计划快照（前端历史回放）
        AgentEvent done = events.get(events.size() - 1);
        assertThat(done.getPlan()).hasSize(2);
        assertThat(done.getPlanTitle()).isEqualTo("编译修复");
        // 工具定义透出：内置 plan_task 无需 DB 配置即可被模型调用
        assertThat(llmClient.recordedRequests.get(0).getTools())
                .anyMatch(t -> "plan_task".equals(t.getName()));
        // 内置工具本地执行，不走外部 ToolExecutor
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("内置文件工具：write_file/edit_file 经 FileWorkspaceSpi 执行（S9 F3）")
    void fileToolsExecuteViaSpi() {
        // 假文件工作空间（内存 Map）+ 重建执行器
        FakeFileWorkspace fake = new FakeFileWorkspace();
        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 10),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                noOpMetricService(),
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                fake,
                null,
                null,
                null);

        // 第一轮：模型创建文件（同轮工具并行执行，依赖前序结果的编辑应在下一轮）
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-1").name("write_file").build()).build(),
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .arguments("{\"path\":\"scripts/hello.sh\",\"content\":\"#!/usr/bin/env bash\\necho v1\"}")
                        .build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        // 第二轮：编辑文件
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-2").name("edit_file").build()).build(),
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .arguments("{\"path\":\"scripts/hello.sh\",\"old_text\":\"echo v1\",\"new_text\":\"echo v2\"}")
                        .build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        // 第三轮：最终回答
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("文件已创建并编辑").build(),
                LlmChunk.builder().finishReason("stop").build()));

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        // 事件结构：tool_call -> tool_executing -> tool_result ->（第二轮）-> content -> done
        assertThat(types).containsSubsequence("tool_call", "tool_executing",
                "tool_result", "content", "done");
        // 文件工具定义透出（无需 DB 配置）
        assertThat(llmClient.recordedRequests.get(0).getTools())
                .anyMatch(t -> "write_file".equals(t.getName()))
                .anyMatch(t -> "read_file".equals(t.getName()));
        // 假工作空间收到写入与编辑后的最终内容
        assertThat(fake.files.get("scripts/hello.sh")).isEqualTo("#!/usr/bin/env bash\necho v2");
        // 内置工具不走外部 ToolExecutor
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
    }

    /** 内存版文件工作空间（测试专用） */
    static class FakeFileWorkspace implements com.gewu.agent.engine.tool.FileWorkspaceSpi {
        final java.util.Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String readFile(com.gewu.agent.engine.tool.ToolContext ctx, String path) {
            return files.get(path);
        }

        @Override
        public void writeFile(com.gewu.agent.engine.tool.ToolContext ctx, String path, String content) {
            files.put(path, content);
        }

        @Override
        public java.util.List<String> listDir(com.gewu.agent.engine.tool.ToolContext ctx, String path) {
            return List.copyOf(files.keySet());
        }
    }

    private TraceService noOpTraceService() {
        return new TraceService() {
            @Override
            public void recordTrace(String executionId, String nodeId, String phase,
                                    String action, String detail) {
            }
        };
    }

    @Test
    @DisplayName("轮次超限回退路径（limit-summary 关闭）：ERROR 带 metadata.reason + DONE(rounds)，账本记失败")
    void streamToolRoundsExceededEmitsErrorAndDone() {
        // 账本观测：捕获指标名（轮次超限应记 agent.task.failure，不再误记成功）
        List<String> metricNames = new CopyOnWriteArrayList<>();
        MetricService recordingMetrics = (name, value, tags) -> metricNames.add(name);

        // 重建执行器：闸门等级感知后轮次上限取预算账本 maxRounds=2（L2）；
        // 关闭优雅降级，验证回退终止序列（优化1 的失败兜底行为）
        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .limitSummaryEnabled(false)
                        // 本用例验证轮次超限的回退终止序列，关闭滚动扩容保持闸门语义
                        .roundsRenewEnabled(false)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 2),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                recordingMetrics,
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // 两轮均请求工具：round 0/1 执行完回灌，round 2 入口 round=2 >= maxRounds=2 触发超限
        for (int i = 0; i < 2; i++) {
            llmClient.scriptedRounds.add(Flux.just(
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id("call-" + i).name("query_weather").build()).build(),
                    LlmChunk.builder().finishReason("tool_calls").build()));
        }
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        // 最后两个事件：ERROR（带 metadata.reason）→ DONE(finishReason=rounds)，
        // 前端生命周期完整（此前 ERROR 后流静默结束，表现为"无声中断"）
        AgentEvent errorEvent = events.get(events.size() - 2);
        AgentEvent doneEvent = events.get(events.size() - 1);
        assertThat(errorEvent.getType()).isEqualTo("error");
        assertThat(errorEvent.getErrorMessage()).isEqualTo("工具调用轮次超限");
        assertThat(errorEvent.getMetadata()).containsEntry("reason", "tool_rounds_exceeded");
        assertThat(doneEvent.getType()).isEqualTo("done");
        assertThat(doneEvent.getFinishReason()).isEqualTo("rounds");
        // 账本语义对齐预算熔断：轮次超限记失败，部分进度不沉淀经验
        assertThat(metricNames).contains("agent.task.failure");
        assertThat(metricNames).doesNotContain("agent.task.success");
    }

    @Test
    @DisplayName("轮次超限优雅降级（优化1）：WARNING+STATUS 后总结收尾，DONE(rounds)，账本记失败")
    void streamToolRoundsExceededDegradesToSummary() {
        List<String> metricNames = new CopyOnWriteArrayList<>();
        MetricService recordingMetrics = (name, value, tags) -> metricNames.add(name);

        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        // 本用例验证轮次超限的优雅降级总结，关闭滚动扩容保持闸门语义
                        .roundsRenewEnabled(false)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 2),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                recordingMetrics,
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // 两轮工具调用触发轮次超限，第 3 次调用为总结响应
        for (int i = 0; i < 2; i++) {
            llmClient.scriptedRounds.add(Flux.just(
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id("call-" + i).name("query_weather").build()).build(),
                    LlmChunk.builder().finishReason("tool_calls").build()));
        }
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("已完成查询，结果为晴天").build(),
                LlmChunk.builder().finishReason("stop").build()));
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        // 优雅降级序列：WARNING（达上限提示）→ STATUS（总结中）→ ... → CONTENT（总结正文）→ DONE(rounds)
        assertThat(types).containsSubsequence("budget_warning", "status", "content", "done");
        assertThat(events).anyMatch(e -> "budget_warning".equals(e.getType())
                && e.getContent() != null && e.getContent().contains("已达工具调用轮次上限"));
        assertThat(events).anyMatch(e -> "content".equals(e.getType())
                && "已完成查询，结果为晴天".equals(e.getContent()));
        // 无 ERROR 事件——降级完成而非裸报错
        assertThat(types).doesNotContain("error");
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo("done");
        assertThat(last.getFinishReason()).isEqualTo("rounds");
        // 总结请求的 maxTokens 取配置上限（默认 8192），不随当轮任务级 maxTokens（2048）缩小
        assertThat(llmClient.recordedRequests).hasSize(3);
        assertThat(llmClient.recordedRequests.get(2).getMaxTokens()).isEqualTo(8192);
        // 账本语义不变：超限仍记失败、不沉淀经验
        assertThat(metricNames).contains("agent.task.failure");
        assertThat(metricNames).doesNotContain("agent.task.success");
    }

    @Test
    @DisplayName("轮次滚动扩容：到顶且无死循环迹象时扩容续跑，DONE 携带执行统计 metadata")
    void streamRoundsExhaustedAutoRenewsWhenProgressing() {
        // 定制执行器：maxRounds=2（L2）；round 2 闸门触发扩容 +10 → 12 轮，
        // 第 3 次 LLM 调用给出最终回答（默认 roundsRenewEnabled=true）
        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 2),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                noOpMetricService(),
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-0").name("query_weather").build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-1").name("query_weather").build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("任务完成").build(),
                LlmChunk.builder().finishReason("stop").build()));
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        // 扩容告警事件出现，且闸门放行后任务继续推进（无强制总结的 STATUS/DONE(rounds)）
        assertThat(events).anyMatch(e -> "budget_warning".equals(e.getType())
                && e.getContent() != null && e.getContent().contains("已自动扩容"));
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        assertThat(types).doesNotContain("error");
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo("done");
        assertThat(last.getFinishReason()).isEqualTo("stop");
        // 完成透明度：DONE metadata 携带执行统计（rounds 含扩容后轮次，roundsRenewed=1）
        assertThat(last.getMetadata()).isNotNull();
        assertThat(last.getMetadata().get("rounds")).isEqualTo(3);
        assertThat(last.getMetadata().get("roundsRenewed")).isEqualTo(1);
        assertThat(last.getMetadata().get("timeRenewals")).isEqualTo(0);
        // 扩容后闸门放行：第三次 LLM 调用正常发生（非 tools 总结请求）
        assertThat(llmClient.recordedRequests).hasSize(3);
    }

    @Test
    @DisplayName("轮次滚动扩容次数耗尽后仍强制总结（绝对上限防失控）")
    void streamRoundsExhaustedForcesSummaryWhenRenewalsExhausted() {
        List<String> metricNames = new CopyOnWriteArrayList<>();
        MetricService recordingMetrics = (name, value, tags) -> metricNames.add(name);

        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .roundsRenewMax(1)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                new BudgetController(81920, 300000, 2),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                recordingMetrics,
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // round 2 闸门扩容一次（maxRounds 2→12），rounds 2..11 继续工具调用，
        // round 12 闸门：扩容次数已耗尽 → 强制总结（13 次 LLM 调用 = 12 轮 + 总结）
        for (int i = 0; i < 12; i++) {
            llmClient.scriptedRounds.add(Flux.just(
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id("call-" + i).name("query_weather")
                            .arguments("{\"i\":" + i + "}").build()).build(),
                    LlmChunk.builder().finishReason("tool_calls").build()));
        }
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("进度总结").build(),
                LlmChunk.builder().finishReason("stop").build()));
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        // 恰好一次扩容告警，随后是强制总结（notice 达上限 + DONE(rounds)）
        long renewWarnings = events.stream()
                .filter(e -> "budget_warning".equals(e.getType()))
                .filter(e -> e.getContent() != null && e.getContent().contains("已自动扩容"))
                .count();
        assertThat(renewWarnings).isEqualTo(1);
        assertThat(events).anyMatch(e -> "budget_warning".equals(e.getType())
                && e.getContent() != null && e.getContent().contains("已达工具调用轮次上限"));
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getFinishReason()).isEqualTo("rounds");
        assertThat(llmClient.recordedRequests).hasSize(13);
    }

    @Test
    @DisplayName("死循环检测（优化3）：warn 阈值注入 nudge，stop 阈值终止并总结（DONE(loop)）")
    void streamToolLoopDetectedNudgesThenStops() {
        List<String> metricNames = new CopyOnWriteArrayList<>();
        MetricService recordingMetrics = (name, value, tags) -> metricNames.add(name);

        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .loopWarnThreshold(2)
                        .loopStopThreshold(3)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                // maxRounds=20：让循环检测先于轮次闸门触发，证明是检测逻辑在起作用
                new BudgetController(81920, 300000, 20),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                recordingMetrics,
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // 三轮完全相同的工具调用 + 总结响应
        for (int i = 0; i < 3; i++) {
            llmClient.scriptedRounds.add(Flux.just(
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .id("call-" + i).name("query_weather").build()).build(),
                    LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                            .arguments("{\"city\":\"北京\"}").build()).build(),
                    LlmChunk.builder().finishReason("tool_calls").build()));
        }
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("循环总结：已连续查询天气").build(),
                LlmChunk.builder().finishReason("stop").build()));
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        List<String> types = events.stream().map(AgentEvent::getType).toList();
        // round 1 达 warn 阈值：注入 nudge + 提示事件；round 2 达 stop 阈值：终止 + 总结
        assertThat(events).anyMatch(e -> "budget_warning".equals(e.getType())
                && e.getContent() != null && e.getContent().contains("已提示模型改变策略"));
        assertThat(events).anyMatch(e -> "budget_warning".equals(e.getType())
                && e.getContent() != null && e.getContent().contains("已自动停止并总结进度"));
        assertThat(types).containsSubsequence("status", "content", "done");
        assertThat(events).anyMatch(e -> "content".equals(e.getType())
                && "循环总结：已连续查询天气".equals(e.getContent()));
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo("done");
        assertThat(last.getFinishReason()).isEqualTo("loop");
        // round 0/1 的工具已执行，round 2 达终止阈值不再执行（节省执行成本）
        verify(toolExecutor, times(2)).execute(eq("query_weather"), anyString(), any(), any());
        // nudge 消息注入 round 2 的 LLM 请求（role=user，含系统提示语）
        List<Message> round3Messages = llmClient.recordedRequests.get(2).getMessages();
        assertThat(round3Messages).anyMatch(m -> "user".equals(m.getRole())
                && m.getContent() != null && m.getContent().contains("系统提示"));
        assertThat(metricNames).contains("agent.task.failure");
    }

    @Test
    @DisplayName("预算熔断优雅降级（优化1）：BUDGET_EXCEEDED banner 保留 + 总结收尾，DONE(budget)")
    void streamBudgetExceededDegradesToSummary() {
        List<String> metricNames = new CopyOnWriteArrayList<>();
        MetricService recordingMetrics = (name, value, tags) -> metricNames.add(name);

        executor = new ReactAgentExecutor(
                new LlmClientRegistry(List.of(llmClient), null, objectMapper,
                        HttpClient.newHttpClient(), new LlmRequestBodyBuilder(objectMapper)),
                toolExecutor,
                new DefaultMessageBuilder(new SystemPromptComposer()),
                new NoOpSessionContextService(),
                new NoOpPersistenceService(),
                AgentEngineConfig.builder()
                        .maxToolRounds(5)
                        .defaultMaxTokens(8192)
                        .defaultTemperature(0.7)
                        .toolExecutor(toolExecutorPool)
                        .defaultHistoryLimit(50)
                        .build(),
                objectMapper,
                new NoOpMemoryRouter(),
                new NoOpMemoryStore(),
                // tokenBudget=2：第 0 轮 consume 后即达 100%，round 1 入口熔断
                new BudgetController(2, 300000, 10),
                new NoOpPerceptionEngine(),
                new ComplexityRouter(new DualSystemRouter()),
                noOpTraceService(),
                recordingMetrics,
                new ResponseCache() {
                },
                new PromptInjectionDetector(),
                new OutputSanitizer(),
                noOpModelSelector(),
                null,
                null,
                null,
                null);

        // 第 0 轮工具调用（触发 consume），第 1 轮入口熔断 → 总结响应
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().toolCallDelta(LlmChunk.ToolCallDelta.builder()
                        .id("call-0").name("query_weather").build()).build(),
                LlmChunk.builder().finishReason("tool_calls").build()));
        llmClient.scriptedRounds.add(Flux.just(
                LlmChunk.builder().delta("预算总结：已查询天气").build(),
                LlmChunk.builder().finishReason("stop").build()));
        when(toolExecutor.execute(eq("query_weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴").build());

        List<AgentEvent> events = executor.executeStream(task(2048))
                .collectList().block();

        assertThat(events).isNotNull();
        // BUDGET_EXCEEDED 保留（前端 banner 照常），随后总结收尾
        assertThat(events).anyMatch(e -> "budget_exceeded".equals(e.getType())
                && e.getErrorMessage() != null && e.getErrorMessage().contains("预算耗尽"));
        assertThat(events).anyMatch(e -> "content".equals(e.getType())
                && "预算总结：已查询天气".equals(e.getContent()));
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo("done");
        assertThat(last.getFinishReason()).isEqualTo("budget");
        assertThat(metricNames).contains("agent.task.failure");
    }

    private MetricService noOpMetricService() {
        return (name, value, tags) -> {
        };
    }

    private ModelSelector noOpModelSelector() {
        return (taskDescription, complexity, privacyLevel, latencyPreference, budgetRemaining) -> null;
    }
}
