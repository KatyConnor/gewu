package com.gewu.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSchemaValidator {

    private final ObjectMapper objectMapper;

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

        if ("object".equals(type) && schemaNode.has("properties")) {
            if (!argsNode.isObject()) {
                errors.add(path.isEmpty() ? "期望对象类型" : path + ": 期望对象类型");
                return;
            }
            JsonNode required = schemaNode.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode req : required) {
                    String fieldName = req.asText();
                    if (!argsNode.has(fieldName)) {
                        errors.add((path.isEmpty() ? "" : path + ".") + fieldName + ": 必填字段缺失");
                    }
                }
            }
            JsonNode properties = schemaNode.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (properties.has(field.getKey())) {
                    validateNode(properties.get(field.getKey()), field.getValue(),
                            (path.isEmpty() ? "" : path + ".") + field.getKey(), errors);
                }
            }
        } else if ("array".equals(type)) {
            if (!argsNode.isArray()) {
                errors.add(path.isEmpty() ? "期望数组类型" : path + ": 期望数组类型");
            }
        } else if ("string".equals(type)) {
            if (!argsNode.isTextual()) {
                errors.add(path.isEmpty() ? "期望字符串类型" : path + ": 期望字符串类型");
            }
        } else if ("number".equals(type) || "integer".equals(type)) {
            if (!argsNode.isNumber()) {
                errors.add(path.isEmpty() ? "期望数字类型" : path + ": 期望数字类型");
            }
        } else if ("boolean".equals(type)) {
            if (!argsNode.isBoolean()) {
                errors.add(path.isEmpty() ? "期望布尔类型" : path + ": 期望布尔类型");
            }
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
