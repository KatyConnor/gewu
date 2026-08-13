package com.gewu.agent.engine.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 制品契约 - 结构化 Schema 强制校验的制品流转规范。
 * <p>定义制品类型、结构 Schema、校验规则与依赖的上游制品。
 * 编排器在节点输出前强制校验契约，不兼容则拒绝流转。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactContract {

    /** 制品类型: prd / design / code / test / ops */
    private String artifactType;

    /** 版本 */
    private String version;

    /** 制品结构定义（JSON Schema） */
    private Map<String, Object> schema;

    /** 校验规则 */
    private List<ValidationRule> validationRules;

    /** 依赖的上游制品 */
    private List<String> dependencies;

    /**
     * 校验规则。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationRule {
        /** 规则名 */
        private String name;
        /** 规则类型: required / format / range / custom */
        private String type;
        /** 校验表达式 */
        private String expression;
        /** 失败消息 */
        private String message;
    }
}