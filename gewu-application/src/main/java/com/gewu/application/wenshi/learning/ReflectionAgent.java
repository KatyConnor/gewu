package com.gewu.application.wenshi.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 反思代理 - 对失败经验进行根因分析。
 * <p>
 * 采用"规则优先 + LLM 兜底"的分层分析策略：
 * <ul>
 *   <li>规则匹配：识别 SQL 语法错误、超时、权限不足、连接失败等已知模式</li>
 *   <li>LLM 分析：无法匹配已知模式时，调用 LLM 进行深度根因分析</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionAgent {

    private final ExperienceMapper experienceMapper;
    private final LlmClientFactory llmClientFactory;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 对指定经验进行反思分析。
     * <p>
     * 先尝试规则匹配已知失败模式，无法匹配时调用 LLM 进行深度分析。
     *
     * @param experienceId 经验 ID（String，与 Experience.id 类型一致）
     * @return 反思报告，包含根因和改进方案
     * @since 1.0.0
     */
    public ReflectionReport reflect(String experienceId) {
        Experience experience = experienceMapper.selectById(experienceId);
        if (experience == null) {
            return ReflectionReport.builder()
                    .experienceId(experienceId)
                    .rootCause("经验不存在")
                    .method("NOT_FOUND")
                    .build();
        }

        // 优先使用规则匹配，降低 LLM 调用成本
        String knownPattern = tryRuleMatch(experience);
        if (knownPattern != null) {
            return ReflectionReport.builder()
                    .experienceId(experienceId)
                    .rootCause(knownPattern)
                    .improvement("已知模式修复: " + knownPattern)
                    .method("RULE_MATCH")
                    .build();
        }

        // 未知模式：调用 LLM 进行深度分析
        return reflectViaLlm(experienceId, experience);
    }

    /**
     * 调用 LLM 对失败经验进行深度根因分析。
     */
    private ReflectionReport reflectViaLlm(String experienceId, Experience experience) {
        try {
            String prompt = "分析以下失败经验的根因和改进建议，以JSON返回：{\"rootCause\":\"...\",\"improvement\":\"...\"}\n"
                    + "场景：" + experience.getScenario() + "\n"
                    + "策略：" + (experience.getStrategy() != null ? experience.getStrategy().substring(0, Math.min(500, experience.getStrategy().length())) : "null");

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(defaultLlmModel)
                    .messages(List.of(
                            Message.builder().role("system").content("你是故障分析专家，擅长根因分析。").build(),
                            Message.builder().role("user").content(prompt).build()))
                    .temperature(0.3)
                    .maxTokens(512)
                    .stream(false)
                    .build();

            LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
            LlmResponse response = client.chat(llmRequest);
            String content = response.getContent();

            // 解析 JSON 响应
            String rootCause = "LLM 分析完成";
            String improvement = "参见根因描述";
            try {
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    JsonNode node = OBJECT_MAPPER.readTree(content.substring(start, end + 1));
                    if (node.has("rootCause")) rootCause = node.get("rootCause").asText();
                    if (node.has("improvement")) improvement = node.get("improvement").asText();
                }
            } catch (Exception parseEx) {
                rootCause = content.substring(0, Math.min(200, content.length()));
            }

            log.info("ReflectionAgent.reflectViaLlm: experienceId={}, rootCause={}", experienceId, rootCause);
            return ReflectionReport.builder()
                    .experienceId(experienceId)
                    .rootCause(rootCause)
                    .improvement(improvement)
                    .method("LLM_ANALYSIS")
                    .build();
        } catch (Exception e) {
            log.warn("ReflectionAgent.reflectViaLlm: LLM call failed: {}", e.getMessage());
            return ReflectionReport.builder()
                    .experienceId(experienceId)
                    .rootCause("LLM 分析失败: " + e.getMessage())
                    .improvement("建议人工复盘")
                    .method("LLM_FAILED")
                    .build();
        }
    }

    private String tryRuleMatch(Experience experience) {
        String strategy = experience.getStrategy();
        if (strategy == null) return null;

        if (strategy.contains("SQL") && strategy.contains("syntax")) {
            return "SQL 语法错误";
        }
        if (strategy.contains("timeout") || strategy.contains("超时")) {
            return "执行超时";
        }
        if (strategy.contains("permission") || strategy.contains("权限")) {
            return "权限不足";
        }
        if (strategy.contains("connection") || strategy.contains("连接")) {
            return "连接失败";
        }
        return null;
    }

    /**
     * 反思报告 - 封装根因分析结论。
     */
    @Data
    @Builder
    public static class ReflectionReport {
        /** 关联的经验 ID */
        private String experienceId;
        /** 根因描述 */
        private String rootCause;
        /** 改进方案 */
        private String improvement;
        /** 分析方法（RULE_MATCH / LLM_ANALYSIS / LLM_FAILED / NOT_FOUND） */
        private String method;
    }
}
