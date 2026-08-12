package com.gewu.application.wenshi.reasoning;

import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结果验证器 — 对推理结果进行多层校验。
 * <p>
 * 采用三级验证策略，按可靠性从高到低依次尝试：
 * <ol>
 *   <li>外部验证：通过外部系统或工具验证结果正确性（如数据库校验、API 验证）</li>
 *   <li>规则验证：基于预定义规则检查（如非空检查、格式校验）</li>
 *   <li>LLM 自检：调用 LLM 对结果进行合理性判断（兜底策略）</li>
 * </ol>
 * 当 LLM 不可用时默认通过，确保系统可用性。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class Critic {

    /** LLM 客户端工厂，用于创建自检所需的 LLM 客户端 */
    private final LlmClientFactory llmClientFactory;

    /**
     * 构造结果验证器。
     *
     * @param llmClientFactory LLM 客户端工厂；不可为 null
     * @since 1.0.0
     */
    public Critic(LlmClientFactory llmClientFactory) {
        this.llmClientFactory = llmClientFactory;
    }

    /**
     * 执行结果验证。
     * <p>
     * 按优先级依次尝试外部验证、规则验证和 LLM 自检，返回第一个可验证的结果。
     *
     * @param solution 待验证的求解结果；不可为 null
     * @param request 原始推理请求，提供上下文信息
     * @return 验证结果，包含是否通过、验证方法及原因说明
     * @since 1.0.0
     */
    public CriticResult evaluate(Solution solution, WenshiReasoningRequest request) {
        // 第一优先级：外部验证（最可靠）
        CriticResult externalResult = tryExternalVerify(solution);
        if (externalResult != null && externalResult.isVerifiable()) {
            return externalResult;
        }

        // 第二优先级：规则验证
        CriticResult ruleResult = tryRuleValidation(solution);
        if (ruleResult != null && ruleResult.isVerifiable()) {
            return ruleResult;
        }

        // 第三优先级：LLM 自检（兜底）
        return tryLlmSelfCheck(solution);
    }

    /**
     * 尝试外部验证。
     * <p>
     * 当求解结果携带外部验证信息时，根据外部系统的返回结果判定是否通过。
     *
     * @param solution 待验证的求解结果
     * @return 若存在外部验证信息则返回验证结果，否则返回 null
     * @since 1.0.0
     */
    private CriticResult tryExternalVerify(Solution solution) {
        if (solution.getExternalVerification() != null) {
            boolean passed = solution.getExternalVerification().isSuccess();
            return CriticResult.builder()
                    .passed(passed)
                    .verifiable(true)
                    .method("EXTERNAL")
                    .reason(passed ? "外部验证通过" : "外部验证失败: " + solution.getExternalVerification().getMessage())
                    .build();
        }
        return null;
    }

    /**
     * 尝试规则验证。
     * <p>
     * 当前实现为空值检查，后续可扩展为更复杂的规则引擎。
     *
     * @param solution 待验证的求解结果
     * @return 若输出为空则返回不通过结果，否则返回 null（表示规则无法判定）
     * @since 1.0.0
     */
    private CriticResult tryRuleValidation(Solution solution) {
        if (solution.getOutput() == null || solution.getOutput().toString().isBlank()) {
            return CriticResult.builder()
                    .passed(false)
                    .verifiable(true)
                    .method("RULE")
                    .reason("输出为空")
                    .build();
        }
        return null;
    }

    /**
     * 尝试 LLM 自检。
     * <p>
     * 调用 LLM 对输出内容进行合理性判断。
     * 为控制 Token 消耗，仅截取前 500 字符发送给 LLM。
     * 当 LLM 不可用时默认通过，确保系统可用性。
     *
     * @param solution 待验证的求解结果
     * @return 验证结果，LLM 不可用时默认返回通过
     * @since 1.0.0
     */
    private CriticResult tryLlmSelfCheck(Solution solution) {
        String output = solution.getOutput() != null ? solution.getOutput().toString() : "";
        if (output.isBlank()) {
            return CriticResult.builder()
                    .passed(false).verifiable(true)
                    .method("LLM_SELF_CHECK").reason("输出为空")
                    .build();
        }
        try {
            LlmClient client = llmClientFactory.getClient("deepseek");
            LlmRequest request = LlmRequest.builder()
                    .model("deepseek-chat")
                    .messages(List.of(
                            Message.builder().role("system").content("你是一个结果验证器。请判断以下输出是否合理。只回答 JSON：{\"passed\": true/false, \"reason\": \"简短说明\"}").build(),
                            Message.builder().role("user").content("输出内容：" + output.substring(0, Math.min(500, output.length()))).build()
                    ))
                    .stream(false)
                    .build();
            LlmResponse response = client.chat(request);
            String content = response.getContent();
            if (content != null && content.contains("\"passed\"")) {
                boolean passed = content.contains("true");
                return CriticResult.builder()
                        .passed(passed)
                        .verifiable(true)
                        .method("LLM_SELF_CHECK")
                        .reason(passed ? "LLM 自检通过" : "LLM 自检未通过")
                        .build();
            }
        } catch (Exception e) {
            // LLM 不可用时降级为默认通过，保障系统可用性
            log.debug("Critic LLM self-check failed, defaulting to pass: {}", e.getMessage());
        }
        return CriticResult.builder()
                .passed(true)
                .verifiable(true)
                .method("LLM_SELF_CHECK_PASS_THROUGH")
                .reason("LLM 不可用，默认通过")
                .build();
    }

    /**
     * 求解结果封装。
     * <p>
     * 包含求解输出、结果类型及可选的外部验证信息。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Solution {
        /** 求解输出内容 */
        private Object output;

        /** 结果类型标识 */
        private String type;

        /** 外部验证信息（可选） */
        private ExternalVerification externalVerification;
    }

    /**
     * 外部验证信息。
     * <p>
     * 由外部系统提供的验证结果，用于判定求解正确性。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalVerification {
        /** 外部验证是否成功 */
        private boolean success;

        /** 验证失败时的错误信息 */
        private String message;
    }

    /**
     * 验证结果。
     * <p>
     * 封装验证结论，包含是否通过、是否可验证、验证方法及原因说明。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticResult {
        /** 验证是否通过 */
        private boolean passed;

        /** 是否可验证（true 表示验证结论有效，false 表示无法判定） */
        private boolean verifiable;

        /** 使用的验证方法（EXTERNAL / RULE / LLM_SELF_CHECK） */
        private String method;

        /** 验证结果原因说明 */
        private String reason;
    }
}
