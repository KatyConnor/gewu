package com.gewu.agent.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmRequestBodyBuilder} 单元测试。
 * <p>重点验证多轮工具对话协议：assistant 消息的 tool_calls 声明序列化
 * （缺失时 tool 消息的 tool_call_id 无来源，严格校验供应商返回 400）。
 */
@DisplayName("LLM 请求体构建器")
class LlmRequestBodyBuilderTest {

    private LlmRequestBodyBuilder builder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        builder = new LlmRequestBodyBuilder(objectMapper);
    }

    @Test
    @DisplayName("四种角色的基础字段序列化：role/content/tool_call_id/name")
    void buildMessagesArrayBasics() throws Exception {
        List<Message> messages = List.of(
                Message.builder().role("system").content("系统提示").build(),
                Message.builder().role("user").content("用户问题").build(),
                Message.builder().role("assistant").content("回答").build(),
                Message.builder().role("tool").content("工具结果")
                        .toolCallId("call-1").name("weather").build());

        JsonNode array = objectMapper.readTree(builder.buildMessagesArray(messages).toString());

        assertThat(array).hasSize(4);
        assertThat(array.get(0).get("role").asText()).isEqualTo("system");
        assertThat(array.get(0).get("content").asText()).isEqualTo("系统提示");
        assertThat(array.get(3).get("role").asText()).isEqualTo("tool");
        assertThat(array.get(3).get("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(array.get(3).get("name").asText()).isEqualTo("weather");
    }

    @Test
    @DisplayName("assistant 消息携带 tool_calls 时按 OpenAI 规范序列化")
    void assistantToolCallsSerialized() throws Exception {
        Message assistant = Message.builder()
                .role("assistant")
                .content(null)
                .toolCalls(List.of(
                        ToolCall.builder().id("call-1").name("weather")
                                .arguments("{\"city\":\"北京\"}").build(),
                        ToolCall.builder().id("call-2").name("time").arguments(null).build()))
                .build();

        JsonNode msg = objectMapper.readTree(
                builder.buildMessagesArray(List.of(assistant)).get(0).toString());

        assertThat(msg.has("tool_calls")).isTrue();
        JsonNode calls = msg.get("tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("id").asText()).isEqualTo("call-1");
        assertThat(calls.get(0).get("type").asText()).isEqualTo("function");
        assertThat(calls.get(0).get("function").get("name").asText()).isEqualTo("weather");
        assertThat(calls.get(0).get("function").get("arguments").asText())
                .isEqualTo("{\"city\":\"北京\"}");
        // arguments 为 null 时输出空对象而非字符串 "null"，避免供应商解析失败
        assertThat(calls.get(1).get("function").get("arguments").asText()).isEqualTo("{}");
    }

    @Test
    @DisplayName("toolCalls 为 null 或空列表时不输出 tool_calls 字段")
    void noToolCallsFieldWhenAbsent() throws Exception {
        List<Message> messages = List.of(
                Message.builder().role("assistant").content("普通回答").build(),
                Message.builder().role("assistant").content("空调用列表").toolCalls(List.of()).build());

        JsonNode array = objectMapper.readTree(builder.buildMessagesArray(messages).toString());
        assertThat(array.get(0).has("tool_calls")).isFalse();
        assertThat(array.get(1).has("tool_calls")).isFalse();
    }

    @Test
    @DisplayName("content 为 null 时省略 content 字段（部分供应商不接受 null content）")
    void nullContentOmitted() throws Exception {
        Message assistant = Message.builder()
                .role("assistant")
                .toolCalls(List.of(ToolCall.builder().id("c1").name("t").arguments("{}").build()))
                .build();
        JsonNode msg = objectMapper.readTree(
                builder.buildMessagesArray(List.of(assistant)).get(0).toString());
        assertThat(msg.has("content")).isFalse();
    }

    @Test
    @DisplayName("tools 数组按 function calling 格式序列化")
    void toolsArrayFormat() throws Exception {
        List<ToolDefinition> tools = List.of(ToolDefinition.builder()
                .name("weather").description("查询天气")
                .parameters("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                .build());

        JsonNode toolsNode = builder.buildToolsArray(tools);
        assertThat(toolsNode).hasSize(1);
        assertThat(toolsNode.get(0).get("type").asText()).isEqualTo("function");
        assertThat(toolsNode.get(0).get("function").get("name").asText()).isEqualTo("weather");
        assertThat(toolsNode.get(0).get("function").get("parameters").get("type").asText())
                .isEqualTo("object");
    }

    @Test
    @DisplayName("完整请求体：model/temperature/max_tokens/stream 与 messages 组装")
    void buildBodyFullRequest() throws Exception {
        LlmRequest request = LlmRequest.builder()
                .model("test-model")
                .temperature(0.3)
                .maxTokens(1234)
                .stream(false)
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        JsonNode body = objectMapper.readTree(
                builder.buildBody(request, false, "test-provider"));

        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1234);
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    @DisplayName("工具 parameters 非法 JSON 时降级跳过且不抛异常")
    void invalidSchemaDegradesGracefully() {
        List<ToolDefinition> tools = List.of(ToolDefinition.builder()
                .name("bad-tool").parameters("not-a-json{{{").build());

        JsonNode toolsNode = builder.buildToolsArray(tools);
        assertThat(toolsNode).hasSize(1);
        assertThat(toolsNode.get(0).get("function").get("name").asText()).isEqualTo("bad-tool");
        assertThat(toolsNode.get(0).get("function").has("parameters")).isFalse();
    }
}
