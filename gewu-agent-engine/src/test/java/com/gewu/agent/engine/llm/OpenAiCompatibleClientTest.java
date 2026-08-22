package com.gewu.agent.engine.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAiCompatibleClient} 同步路径测试（T1.5 D-11 修复验证）。
 * <p>使用 JDK 内置 HttpServer 模拟 OpenAI 兼容接口，验证 reasoning_content
 * 回退条件收紧与可配置请求超时。
 */
@DisplayName("OpenAI 兼容客户端")
class OpenAiCompatibleClientTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger servedCount = new AtomicInteger();

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

    private void respondWith(String jsonBody, long delayMs) {
        server.createContext("/v1/chat/completions", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            servedCount.incrementAndGet();
        });
        server.start();
    }

    private LlmRequest request() {
        return LlmRequest.builder()
                .model("test-model")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .stream(false)
                .build();
    }

    private OpenAiCompatibleClient client(Duration timeout) {
        return new OpenAiCompatibleClient("test", "key", baseUrl, objectMapper,
                java.net.http.HttpClient.newHttpClient(),
                new LlmRequestBodyBuilder(objectMapper), timeout);
    }

    @Test
    @DisplayName("正常回复：content 直读且不标记回退")
    void normalContentParsed() {
        respondWith("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}," +
                "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10," +
                "\"completion_tokens\":5,\"total_tokens\":15}}", 0);

        LlmResponse response = client(Duration.ofSeconds(5)).chat(request());

        assertThat(response.getContent()).isEqualTo("你好");
        assertThat(response.isReasoningFallback()).isFalse();
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
        assertThat(response.getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("D-11: content 空且无工具调用时才回退 reasoning_content 并标记")
    void reasoningFallbackMarked() {
        respondWith("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                "\"reasoning_content\":\"思考过程...\"},\"finish_reason\":\"length\"}]}", 0);

        LlmResponse response = client(Duration.ofSeconds(5)).chat(request());

        assertThat(response.getContent()).isEqualTo("思考过程...");
        assertThat(response.isReasoningFallback()).isTrue();
    }

    @Test
    @DisplayName("D-11: 有工具调用时 content 为空是协议正常态，不回退不标记")
    void noFallbackWhenToolCallsPresent() {
        respondWith("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                "\"reasoning_content\":\"需要调用工具\",\"tool_calls\":[{\"id\":\"call-1\"," +
                "\"type\":\"function\",\"function\":{\"name\":\"weather\"," +
                "\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}", 0);

        LlmResponse response = client(Duration.ofSeconds(5)).chat(request());

        assertThat(response.getContent()).isNull();
        assertThat(response.isReasoningFallback()).isFalse();
        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getId()).isEqualTo("call-1");
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("weather");
    }

    @Test
    @DisplayName("可配置请求超时：服务端延迟超过 requestTimeout 抛超时异常")
    void configurableRequestTimeout() {
        respondWith("{\"choices\":[{\"message\":{\"content\":\"slow\"}}]}", 3000);

        OpenAiCompatibleClient fastTimeout = client(Duration.ofMillis(200));
        assertThatThrownBy(() -> fastTimeout.chat(request()))
                .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(servedCount.get()).isEqualTo(0);
    }
}
