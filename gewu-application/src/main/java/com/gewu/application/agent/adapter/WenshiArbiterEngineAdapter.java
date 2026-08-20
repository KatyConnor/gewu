package com.gewu.application.agent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.cognition.ArbiterEngine;
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 仲裁引擎适配器 - LLM 多采样仲裁，选择最优候选方案。
 * <p>按架构文档仲裁分级：
 * <ul>
 *   <li>默认级：同厂商多采样（n=3 取多数），国产可用</li>
 *   <li>增强级：最强模型仲裁（可选）</li>
 * </ul>
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiArbiterEngineAdapter implements ArbiterEngine {

    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultModel;

    private static final int SAMPLE_COUNT = 3;
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    @Override
    public ArbitrationResult arbitrate(String context, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ArbitrationResult.of(0, "无候选方案", 0.5, "候选为空");
        }
        if (candidates.size() == 1) {
            return ArbitrationResult.of(0, "唯一候选方案", 0.9, "无需仲裁");
        }

        // 多采样仲裁（n=3 取多数）
        int[] votes = new int[candidates.size()];
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            int winner = singleArbitration(context, candidates, sample);
            if (winner >= 0 && winner < votes.length) {
                votes[winner]++;
            }
        }

        // 取得票最多的
        int bestIndex = 0;
        int bestVotes = 0;
        for (int i = 0; i < votes.length; i++) {
            if (votes[i] > bestVotes) {
                bestVotes = votes[i];
                bestIndex = i;
            }
        }

        double confidence = (double) bestVotes / SAMPLE_COUNT;
        String reasoning = String.format("多采样仲裁(n=%d): 候选%d获得%d/%d票", SAMPLE_COUNT,
                bestIndex + 1, bestVotes, SAMPLE_COUNT);
        log.debug("WenshiArbiterEngine: context={}, winner={}, confidence={}", context, bestIndex, confidence);
        return ArbitrationResult.of(bestIndex, "多采样仲裁胜出", confidence, reasoning);
    }

    private int singleArbitration(String context, List<String> candidates, int sampleIndex) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("仲裁上下文: ").append(truncate(context, 500)).append("\n\n候选方案:\n");
            for (int i = 0; i < candidates.size(); i++) {
                prompt.append(String.format("方案%d: %s\n", i + 1, truncate(candidates.get(i), 300)));
            }
            prompt.append("\n请选择最优方案，只返回方案编号(1-").append(candidates.size()).append(")。");

            LlmRequest request = LlmRequest.builder()
                    .model(defaultModel)
                    .messages(List.of(
                            Message.builder().role("system").content("你是仲裁专家，根据上下文选择最优方案。只返回数字编号。").build(),
                            Message.builder().role("user").content(prompt.toString()).build()))
                    .temperature(0.1 + sampleIndex * 0.2) // 不同采样用不同温度
                    .maxTokens(16)
                    .stream(false)
                    .build();

            LlmClient client = llmClientFactory.getClient(defaultProvider);
            LlmResponse response = client.chat(request);
            String content = response.getContent() != null ? response.getContent().trim() : "";

            // 解析数字
            for (int i = 1; i <= candidates.size(); i++) {
                if (content.contains(String.valueOf(i))) {
                    return i - 1;
                }
            }
        } catch (Exception e) {
            log.debug("WenshiArbiterEngine 单次仲裁失败 sample={}: {}", sampleIndex, e.getMessage());
        }
        return -1;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}