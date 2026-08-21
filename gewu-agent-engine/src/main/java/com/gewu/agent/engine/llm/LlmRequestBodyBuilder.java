package com.gewu.agent.engine.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.ToolCall;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * LLM 请求体构建器 - 将内部请求模型序列化为 OpenAI 兼容的 JSON 请求体。
 * <p>组装 model、messages、tools、temperature、max_tokens 等参数，
 * 输出可直接发送给 OpenAI / DeepSeek / 智谱 / 豆包等兼容接口。
 *
 * @since 1.0.0
 */
@Slf4j
public class LlmRequestBodyBuilder {

    private final ObjectMapper objectMapper;

    public LlmRequestBodyBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 构建完整的 LLM 请求 JSON 字符串 */
    public String buildBody(LlmRequest request, boolean stream, String provider) {
        ObjectNode root = objectMapper.createObjectNode();
        addBasicParams(root, request, stream);
        addMessages(root, request.getMessages());
        addTools(root, request.getTools());
        return serialize(root);
    }

    /** 将消息列表序列化为 JSON 数组节点 */
    public ArrayNode buildMessagesArray(List<Message> messages) {
        ArrayNode messagesNode = objectMapper.createArrayNode();
        for (Message msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.getRole());
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
            if (msg.getName() != null) {
                msgNode.put("name", msg.getName());
            }
            // assistant 消息的工具调用声明（OpenAI 规范：[{id, type:"function", function:{name, arguments}}]）。
            // 多轮工具对话缺少该声明时，tool 消息的 tool_call_id 无对应来源，严格校验的供应商会返回 400。
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode calls = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.getToolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", tc.getId());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", tc.getName());
                    fn.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                }
            }
            messagesNode.add(msgNode);
        }
        return messagesNode;
    }

    /** 将工具定义列表序列化为 OpenAI function calling 格式的 JSON 数组 */
    public ArrayNode buildToolsArray(List<ToolDefinition> tools) {
        ArrayNode toolsNode = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", tool.getName());
            if (tool.getDescription() != null) {
                function.put("description", tool.getDescription());
            }
            if (tool.getParameters() != null) {
                try {
                    function.set("parameters", objectMapper.readTree(tool.getParameters()));
                } catch (JsonProcessingException e) {
                    log.warn("解析工具参数 JSON Schema 失败: {}", tool.getName(), e);
                }
            }
            toolNode.set("function", function);
            toolsNode.add(toolNode);
        }
        return toolsNode;
    }

    public String serializeObject(ObjectNode root) {
        return serialize(root);
    }

    private void addBasicParams(ObjectNode root, LlmRequest request, boolean stream) {
        if (request.getModel() != null) {
            root.put("model", request.getModel());
        }
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        root.put("stream", stream);
    }

    private void addMessages(ObjectNode root, List<Message> messages) {
        root.set("messages", buildMessagesArray(messages));
    }

    private void addTools(ObjectNode root, List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        root.set("tools", buildToolsArray(tools));
    }

    private String serialize(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("构建 LLM 请求体失败", e);
        }
    }
}
