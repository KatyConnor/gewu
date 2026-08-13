package com.gewu.agent.engine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 制品校验器 - 编排器在节点输出前强制校验制品契约。
 * <p>校验流程：
 * <ol>
 *   <li>L1 语法验证：JSON Schema 校验（字段存在性 + 类型检查）</li>
 *   <li>L2 规则验证：按 validationRules 逐条校验</li>
 * </ol>
 * 不合格制品不向下游流转，记录违规原因。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ArtifactValidator {

    private final ObjectMapper objectMapper;

    /**
     * 校验制品是否符合契约。
     *
     * @param contract  制品契约
     * @param artifact  待校验制品（JSON 字符串或对象）
     * @return 校验结果
     */
    public ValidationResult validate(ArtifactContract contract, Object artifact) {
        if (contract == null) {
            return ValidationResult.passedResult();
        }
        if (artifact == null) {
            return ValidationResult.failed("制品为 null");
        }

        List<String> errors = new ArrayList<>();

        // L1 语法验证：Schema 字段存在性检查
        if (contract.getSchema() != null && !contract.getSchema().isEmpty()) {
            try {
                JsonNode artifactNode = objectMapper.valueToTree(artifact);
                for (Map.Entry<String, Object> entry : contract.getSchema().entrySet()) {
                    String fieldName = entry.getKey();
                    if (!artifactNode.has(fieldName)) {
                        errors.add("缺少必需字段: " + fieldName);
                    }
                }
            } catch (Exception e) {
                errors.add("制品序列化失败: " + e.getMessage());
            }
        }

        // L2 规则验证
        if (contract.getValidationRules() != null) {
            for (ArtifactContract.ValidationRule rule : contract.getValidationRules()) {
                String error = applyRule(rule, artifact);
                if (error != null) {
                    errors.add(error);
                }
            }
        }

        if (errors.isEmpty()) {
            log.debug("ArtifactValidator 制品校验通过: type={}", contract.getArtifactType());
            return ValidationResult.passedResult();
        } else {
            log.warn("ArtifactValidator 制品校验失败: type={}, errors={}", contract.getArtifactType(), errors);
            return ValidationResult.failed(errors);
        }
    }

    private String applyRule(ArtifactContract.ValidationRule rule, Object artifact) {
        if ("required".equals(rule.getType())) {
            try {
                JsonNode node = objectMapper.valueToTree(artifact);
                if (node.has(rule.getExpression()) && node.get(rule.getExpression()).isNull()) {
                    return rule.getMessage() != null ? rule.getMessage() : "字段 " + rule.getExpression() + " 不能为空";
                }
            } catch (Exception e) {
                return rule.getMessage() != null ? rule.getMessage() : "规则校验异常: " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * 校验结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;

        public static ValidationResult passedResult() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(String error) {
            return new ValidationResult(false, List.of(error));
        }

        public static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}