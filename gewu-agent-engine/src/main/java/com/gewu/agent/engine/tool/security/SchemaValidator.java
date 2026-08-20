package com.gewu.agent.engine.tool.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema 参数校验器 - 校验工具入参是否符合声明的 JSON Schema。
 * <p>支持 object / array / string / number / integer / boolean 类型与 required 必填校验。
 *
 * @since 1.0.0
 */
@Slf4j
public class SchemaValidator implements SecurityCheck {

    private final ObjectMapper objectMapper;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        if (config == null || config.getRequestSchema() == null || config.getRequestSchema().isBlank()) {
            return;
        }
        ValidationResult validation = validate(config.getRequestSchema(), arguments);
        if (!validation.valid()) {
            throw AgentEngineException.of("SCHEMA_INVALID",
                    "参数校验失败: " + String.join(", ", validation.errors()));
        }
    }

    /** 校验参数是否符合 schema */
    public ValidationResult validate(String schema, String arguments) {
        if (schema == null || schema.isBlank()) {
            return new ValidationResult(true, List.of());
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schema);
            JsonNode argsNode = objectMapper.readTree(arguments);
            List<String> errors = new ArrayList<>();
            validateNode(schemaNode, argsNode, "", errors);
            return new ValidationResult(errors.isEmpty(), errors);
        } catch (JsonProcessingException e) {
            return new ValidationResult(false, List.of("JSON 解析失败: " + e.getMessage()));
        }
    }

    private void validateNode(JsonNode schemaNode, JsonNode argsNode, String path, List<String> errors) {
        String type = schemaNode.has("type") ? schemaNode.get("type").asText() : null;
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> validateObject(schemaNode, argsNode, path, errors);
            case "array" -> validateTypeMatch(argsNode.isArray(), "数组", path, errors);
            case "string" -> validateTypeMatch(argsNode.isTextual(), "字符串", path, errors);
            case "number", "integer" -> validateTypeMatch(argsNode.isNumber(), "数字", path, errors);
            case "boolean" -> validateTypeMatch(argsNode.isBoolean(), "布尔", path, errors);
        }
    }

    private void validateObject(JsonNode schemaNode, JsonNode argsNode, String path, List<String> errors) {
        if (!argsNode.isObject()) {
            errors.add(path.isEmpty() ? "期望对象类型" : path + ": 期望对象类型");
            return;
        }
        validateRequiredFields(schemaNode, argsNode, path, errors);
        validateProperties(schemaNode, argsNode, path, errors);
    }

    private void validateRequiredFields(JsonNode schemaNode, JsonNode argsNode, String path, List<String> errors) {
        JsonNode required = schemaNode.get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        for (JsonNode req : required) {
            String fieldName = req.asText();
            if (!argsNode.has(fieldName)) {
                errors.add((path.isEmpty() ? "" : path + ".") + fieldName + ": 必填字段缺失");
            }
        }
    }

    private void validateProperties(JsonNode schemaNode, JsonNode argsNode, String path, List<String> errors) {
        JsonNode properties = schemaNode.get("properties");
        if (properties == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (properties.has(field.getKey())) {
                String childPath = (path.isEmpty() ? "" : path + ".") + field.getKey();
                validateNode(properties.get(field.getKey()), field.getValue(), childPath, errors);
            }
        }
    }

    private void validateTypeMatch(boolean matches, String typeName, String path, List<String> errors) {
        if (!matches) {
            errors.add(path.isEmpty() ? "期望" + typeName + "类型" : path + ": 期望" + typeName + "类型");
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
