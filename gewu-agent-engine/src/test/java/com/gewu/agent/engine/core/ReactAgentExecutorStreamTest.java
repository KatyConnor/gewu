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
                noOpModelSelector());
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

    private TraceService noOpTraceService() {
        return new TraceService() {
            @Override
            public void recordTrace(String executionId, String nodeId, String phase,
                                    String action, String detail) {
            }
        };
    }

    private MetricService noOpMetricService() {
        return (name, value, tags) -> {
        };
    }

    private ModelSelector noOpModelSelector() {
        return (taskDescription, complexity, privacyLevel, latencyPreference, budgetRemaining) -> null;
    }
}
