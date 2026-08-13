package com.gewu.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM-as-Judge 锚点评估器 - 独立评估 LLM 按验收标准对输出打分（0-10分）。
 * <p>关键设计：用验收标准（自然语言）替代期望输出，避免过拟合。
 * 评估使用与执行不同的供应商模型，防止同质化评分虚高。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmJudge {

    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    @Value("${gewu.evaluation.judge-provider:deepseek}")
    private String judgeProvider;

    @Value("${gewu.evaluation.judge-model:deepseek-chat}")
    private String judgeModel;

    /**
     * 评估输出是否满足验收标准。
     *
     * @param anchorCase  锚点用例（含验收标准）
     * @param output      待评估输出
     * @return 评估结果
     */
    public EvaluationResult evaluate(AnchorCase anchorCase, String output) {
        try {
            String prompt = buildPrompt(anchorCase, output);
            LlmRequest request = LlmRequest.builder()
                    .model(judgeModel)
                    .messages(List.of(
                            Message.builder().role("system").content(
                                    "你是质量评估专家。根据验收标准对输出打分（0-10分）。" +
                                    "以JSON返回：{\"score\": 数字, \"verdict\": \"简短说明\", \"passed\": true/false}").build(),
                            Message.builder().role("user").content(prompt).build()))
                    .temperature(0.0)
                    .maxTokens(512)
                    .stream(false)
                    .build();

            LlmClient client = llmClientFactory.getClient(judgeProvider);
            LlmResponse response = client.chat(request);
            return parseResult(response.getContent(), anchorCase);
        } catch (Exception e) {
            log.warn("LlmJudge.evaluate failed: {}", e.getMessage());
            return EvaluationResult.builder()
                    .totalScore(5.0)
                    .passed(false)
                    .verdict("评估失败: " + e.getMessage())
                    .build();
        }
    }

    private String buildPrompt(AnchorCase anchorCase, String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 场景\n").append(anchorCase.getScenario()).append("\n\n");
        sb.append("## 验收标准\n").append(anchorCase.getAcceptanceCriteria()).append("\n\n");
        if (anchorCase.getCriteriaDimensions() != null) {
            sb.append("## 评估维度\n");
            for (CriteriaDim dim : anchorCase.getCriteriaDimensions()) {
                sb.append("- ").append(dim.getDimension()).append(": ").append(dim.getDescription())
                        .append("（阈值≥").append(dim.getThreshold()).append("）\n");
            }
            sb.append("\n");
        }
        sb.append("## 待评估输出\n").append(truncate(output, 2000));
        return sb.toString();
    }

    private EvaluationResult parseResult(String content, AnchorCase anchorCase) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                var node = objectMapper.readTree(content.substring(start, end + 1));
                double score = node.has("score") ? node.get("score").asDouble() : 5.0;
                boolean passed = node.has("passed") && node.get("passed").asBoolean();
                String verdict = node.has("verdict") ? node.get("verdict").asText() : "";
                double threshold = anchorCase.getCriteriaDimensions() != null
                        ? anchorCase.getCriteriaDimensions().stream()
                                .mapToDouble(CriteriaDim::getThreshold).average().orElse(6.0)
                        : 6.0;
                return EvaluationResult.builder()
                        .totalScore(score)
                        .passed(passed && score >= threshold)
                        .verdict(verdict)
                        .caseId(anchorCase.getCaseId())
                        .build();
            }
        } catch (Exception e) {
            log.debug("LlmJudge parse failed: {}", e.getMessage());
        }
        return EvaluationResult.builder()
                .totalScore(5.0).passed(false)
                .verdict("评估结果解析失败")
                .caseId(anchorCase.getCaseId())
                .build();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    // ===== DTO =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnchorCase {
        private String caseId;
        private String scenario;
        private Object input;
        private String acceptanceCriteria;
        private List<CriteriaDim> criteriaDimensions;
        private double weight;
        private List<String> tags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriteriaDim {
        private String dimension;
        private String description;
        private double threshold;
        private double weight;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationResult {
        private String caseId;
        private double totalScore;
        private boolean passed;
        private String verdict;
    }
}