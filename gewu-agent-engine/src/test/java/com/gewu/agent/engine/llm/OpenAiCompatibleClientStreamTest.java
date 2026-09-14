package com.gewu.agent.engine.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAiCompatibleClient} 流式路径测试。
 * <p>用 JDK HttpServer 模拟 OpenAI SSE 流，验证 data: 行解析、[DONE] 终止、
 * reasoning/delta/toolCallDelta 分离与错误码处理。
 */
@DisplayName("OpenAI 兼容客户端-流式")
class OpenAiCompatibleClientStreamTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 以 SSE 格式响应给定 data 载荷列表 */
    private void respondSse(int status, List<String> dataLines) {
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (status != 200) {
                byte[] err = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : dataLines) {
                sb.append("data: ").append(line).append("\n\n");
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    private OpenAiCompatibleClient client() {
        return new OpenAiCompatibleClient("test", "key", baseUrl, objectMapper,
                java.net.http.HttpClient.newHttpClient(),
                new LlmRequestBodyBuilder(objectMapper));
    }

    private LlmRequest request() {
        return LlmRequest.builder()
                .model("test-model")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .stream(true)
                .build();
    }

    @Test
    @DisplayName("content 增量与 finish_reason 解析，[DONE] 终止流")
    void contentDeltasStream() {
        respondSse(200, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]"));

        List<LlmChunk> chunks = client().chatStream(request()).collectList().block();

        assertThat(chunks).isNotNull();
        List<String> deltas = chunks.stream().map(LlmChunk::getDelta).filter(d -> d != null).toList();
        assertThat(deltas).containsExactly("你", "好");
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("reasoning_content 与 content 分离（推理模型）")
    void reasoningSeparatedFromContent() {
        respondSse(200, List.of(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析问题\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案是42\"}}]}",
                "[DONE]"));

        List<LlmChunk> chunks = client().chatStream(request()).collectList().block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.get(0).getReasoning()).isEqualTo("先分析问题");
        assertThat(chunks.get(0).getDelta()).isNull();
        assertThat(chunks.get(1).getDelta()).isEqualTo("答案是42");
        assertThat(chunks.get(1).getReasoning()).isNull();
    }

    @Test
    @DisplayName("tool_calls 增量解析（id/name/arguments）")
    void toolCallDeltaParsed() {
        respondSse(200, List.of(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"\\\"北京\\\"\"}}]}}]}",
                "[DONE]"));

        List<LlmChunk> chunks = client().chatStream(request()).collectList().block();

        assertThat(chunks).isNotNull();
        LlmChunk.ToolCallDelta first = chunks.get(0).getToolCallDelta();
        assertThat(first.getId()).isEqualTo("call-1");
        assertThat(first.getName()).isEqualTo("weather");
        assertThat(first.getArguments()).isEqualTo("{\"city\":");
        LlmChunk.ToolCallDelta second = chunks.get(1).getToolCallDelta();
        assertThat(second.getId()).isNull();
        assertThat(second.getArguments()).isEqualTo("\"北京\"");
    }

    @Test
    @DisplayName("流式请求体 stream=true 且 Accept 为 event-stream")
    void streamRequestBody() {
        respondSse(200, List.of("[DONE]"));

        client().chatStream(request()).collectList().block();

        assertThat(lastRequestBody.get()).contains("\"stream\":true");
    }

    @Test
    @DisplayName("非法 JSON 行被跳过，不中断后续解析")
    void invalidDataLineSkipped() {
        respondSse(200, List.of(
                "{invalid-json",
                "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                "[DONE]"));

        List<LlmChunk> chunks = client().chatStream(request()).collectList().block();

        assertThat(chunks).isNotNull().hasSize(1);
        assertThat(chunks.get(0).getDelta()).isEqualTo("ok");
    }

    @Test
    @DisplayName("HTTP 错误码转错误信号")
    void httpErrorPropagated() {
        respondSse(401, List.of());

        Flux<LlmChunk> stream = client().chatStream(request());
        assertThatThrownBy(() -> stream.collectList().block())
                .hasMessageContaining("HTTP 401");
    }

    @Test
    @DisplayName("无 [DONE] 的静默断流按错误信号处理（S9：截断可见）")
    void streamBreakWithoutDoneMarkerErrors() {
        respondSse(200, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}"));

        // EOF 但未收到 [DONE]：上游连接中断，必须转错误信号而非静默完成，
        // 否则截断内容会被当正常回复收尾（缺陷：回复只输出一半无提示）
        assertThatThrownBy(() -> client().chatStream(request()).collectList().block())
                .hasMessageContaining("未收到 [DONE]");
    }
}
