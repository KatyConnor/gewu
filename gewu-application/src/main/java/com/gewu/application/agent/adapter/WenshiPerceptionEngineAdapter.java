package com.gewu.application.agent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 感知引擎适配器 - LLM 驱动的意图分类 + 实体提取。
 * <p>规则层先行（greeting/faq 关键词匹配 → L1 快速路径），LLM 兜底做深度感知。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiPerceptionEngineAdapter implements PerceptionEngine {

    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultModel;

    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    @Override
    public Intent perceive(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return Intent.unknown(rawInput);
        }

        // 规则层：快速确定性判断（零成本）
        Intent ruleResult = tryRulePerception(rawInput);
        if (ruleResult != null) {
            return ruleResult;
        }

        // LLM 兜底：深度意图分类 + 实体提取
        return perceiveViaLlm(rawInput);
    }

    private Intent tryRulePerception(String input) {
        String lower = input.toLowerCase().trim();

        // 问候/闲聊 → L1 快速
        if (lower.matches("^(你好|hi|hello|嗨|hey|在吗|在不在).*$")) {
            return Intent.builder()
                    .intentId(UUID.randomUUID().toString())
                    .intentType("greeting")
                    .confidence(0.95)
                    .entities(List.of())
                    .entityCount(0)
                    .requiresMultiStep(false)
                    .needClarification(false)
                    .taskLevel("L1")
                    .candidateActions(List.of("respond_greeting"))
                    .build();
        }

        // FAQ/简单查询 → L1
        if (lower.contains("什么是") || lower.contains("怎么用") || lower.contains("如何使用")
                || lower.contains("帮助") || lower.contains("help") || lower.contains("说明")) {
            return Intent.builder()
                    .intentId(UUID.randomUUID().toString())
                    .intentType("faq")
                    .confidence(0.9)
                    .entities(List.of())
                    .entityCount(0)
                    .requiresMultiStep(false)
                    .needClarification(false)
                    .taskLevel("L1")
                    .candidateActions(List.of("answer_faq"))
                    .build();
        }

        // 复杂任务关键词 → L3 深度
        if (lower.contains("架构") || lower.contains("设计") || lower.contains("重构")
                || lower.contains("系统设计") || lower.contains("技术方案")) {
            return Intent.builder()
                    .intentId(UUID.randomUUID().toString())
                    .intentType("design")
                    .confidence(0.85)
                    .entities(extractEntities(input))
                    .requiresMultiStep(true)
                    .needClarification(false)
                    .taskLevel("L3")
                    .candidateActions(List.of("plan_and_execute"))
                    .build();
        }

        return null; // 规则未匹配，委托 LLM
    }

    private Intent perceiveViaLlm(String input) {
        try {
            String systemPrompt = "你是意图分类专家。分析用户输入，识别意图类型和实体。\n"
                    + "意图类型: greeting/faq/task_query/code_gen/info_search/analysis/design/deploy/unknown\n"
                    + "以JSON返回: {\"intentType\":\"...\",\"confidence\":0.0-1.0,\"entities\":[\"实1\",\"实2\"],"
                    + "\"requiresMultiStep\":true/false,\"taskLevel\":\"L1|L2|L3\"}";

            LlmRequest request = LlmRequest.builder()
                    .model(defaultModel)
                    .messages(List.of(
                            Message.builder().role("system").content(systemPrompt).build(),
                            Message.builder().role("user").content(input.substring(0, Math.min(500, input.length()))).build()))
                    .temperature(0.1)
                    .maxTokens(256)
                    .stream(false)
                    .build();

            LlmClient client = llmClientFactory.getClient(defaultProvider);
            LlmResponse response = client.chat(request);
            return parseIntent(response.getContent(), input);
        } catch (Exception e) {
            log.warn("WenshiPerceptionEngineAdapter LLM 感知失败，回退到 unknown: {}", e.getMessage());
            return Intent.unknown(input);
        }
    }

    private Intent parseIntent(String content, String input) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = STATIC_MAPPER.readTree(content.substring(start, end + 1));
                String intentType = node.has("intentType") ? node.get("intentType").asText() : "unknown";
                double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
                List<String> entities = new ArrayList<>();
                if (node.has("entities") && node.get("entities").isArray()) {
                    node.get("entities").forEach(e -> entities.add(e.asText()));
                }
                boolean multiStep = node.has("requiresMultiStep") && node.get("requiresMultiStep").asBoolean();
                String taskLevel = node.has("taskLevel") ? node.get("taskLevel").asText() : "L2";

                return Intent.builder()
                        .intentId(UUID.randomUUID().toString())
                        .intentType(intentType)
                        .confidence(confidence)
                        .entities(entities)
                        .entityCount(entities.size())
                        .requiresMultiStep(multiStep)
                        .needClarification(confidence < 0.7)
                        .taskLevel(taskLevel)
                        .candidateActions(List.of())
                        .build();
            }
        } catch (Exception e) {
            log.debug("感知结果解析失败: {}", e.getMessage());
        }
        return Intent.unknown(input);
    }

    private List<String> extractEntities(String input) {
        // 简单的实体提取：识别引号内容、技术名词
        List<String> entities = new ArrayList<>();
        // 提取引号内容
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"|'([^']+)'|「([^」]+)」")
                .matcher(input);
        while (m.find()) {
            for (int i = 1; i <= 3; i++) {
                if (m.group(i) != null) {
                    entities.add(m.group(i));
                }
            }
        }
        return entities;
    }
}