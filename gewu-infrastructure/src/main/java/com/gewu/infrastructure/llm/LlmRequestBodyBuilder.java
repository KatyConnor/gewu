package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 请求体构建器 — 将内部请求模型序列化为 OpenAI 兼容的 JSON 请求体。
 * <p>
 * 负责组装 model、messages、tools、temperature、max_tokens 等参数，
 * 输出可直接发送给 DeepSeek、OpenAI 等兼容 OpenAI API 的 LLM 服务。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRequestBodyBuilder {

    private final ObjectMapper objectMapper;

    /**
     * 构建完整的 LLM 请求 JSON 字符串。
     *
     * @param request 内部 LLM 请求对象，不可为 null
     * @param stream  是否启用流式响应
     * @param provider LLM 提供方标识（预留，用于未来多提供方差异化处理）
     * @return JSON 格式请求体字符串
     * @throws RuntimeException JSON 序列化失败时抛出
     */
    public String buildBody(LlmRequest request, boolean stream, String provider) {
        ObjectNode root = objectMapper.createObjectNode();
        addBasicParams(root, request, stream);
        addMessages(root, request.getMessages());
        addTools(root, request.getTools(), provider);
        return serialize(root);
    }

    /**
     * 将消息列表序列化为 JSON 数组节点。
     * <p>
     * 每条消息包含 role、content，以及可选的 tool_call_id 和 name。
     *
     * @param messages 消息列表
     * @return JSON 数组节点
     */
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
            messagesNode.add(msgNode);
        }
        return messagesNode;
    }

    /**
     * 将工具定义列表序列化为 OpenAI function calling 格式的 JSON 数组。
     * <p>
     * 每个工具包含 type="function"、name、description 和 parameters（JSON Schema）。
     *
     * @param tools 工具定义列表
     * @return JSON 数组节点
     */
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

    /**
     * 将 ObjectNode 序列化为 JSON 字符串。
     *
     * @param root JSON 对象节点
     * @return JSON 字符串
     * @throws RuntimeException 序列化失败时抛出
     */
    public String serializeObject(ObjectNode root) {
        return serialize(root);
    }

    /**
     * 添加基础请求参数：model、temperature、max_tokens、stream。
     */
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

    /**
     * 添加 messages 数组到请求体。
     */
    private void addMessages(ObjectNode root, List<Message> messages) {
        root.set("messages", buildMessagesArray(messages));
    }

    /**
     * 添加工具定义到请求体（仅当工具列表非空时）。
     */
    private void addTools(ObjectNode root, List<ToolDefinition> tools, String provider) {
        if (tools == null || tools.isEmpty()) return;
        root.set("tools", buildToolsArray(tools));
    }

    /**
     * 将 ObjectNode 序列化为 JSON 字符串。
     *
     * @throws RuntimeException JSON 序列化失败
     */
    private String serialize(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("构建 LLM 请求体失败", e);
        }
    }
}
