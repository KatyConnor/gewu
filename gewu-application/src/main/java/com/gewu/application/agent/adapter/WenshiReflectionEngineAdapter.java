package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.cognition.ReflectionEngine;
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

/**
 * ReflectionEngine SPI 适配器 - 桥接 Wenshi 反思能力到 Agent 引擎。
 * <p>使用 LLM 对执行结果进行根因分析与改进建议，
 * 采用与 Wenshi ReflectionAgent 相同的反思提示模式。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiReflectionEngineAdapter implements ReflectionEngine {

    private final LlmClientFactory llmClientFactory;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    /**
     * 反思执行结果，返回根因分析与改进建议。
     */
    @Override
    public String reflect(String executionId, String result, String goal) {
        try {
            String truncatedResult = result != null
                    ? result.substring(0, Math.min(500, result.length()))
                    : "无结果";
            String prompt = "分析以下执行结果的根因和改进建议，以JSON返回："
                    + "{\"rootCause\":\"...\",\"improvement\":\"...\"}\n"
                    + "目标：" + goal + "\n"
                    + "结果：" + truncatedResult;
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(defaultLlmModel)
                    .messages(List.of(
                            Message.builder().role("system").content("你是执行反思专家，擅长根因分析和改进建议。").build(),
                            Message.builder().role("user").content(prompt).build()))
                    .temperature(0.3)
                    .maxTokens(512)
                    .stream(false)
                    .build();
            LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
            LlmResponse response = client.chat(llmRequest);
            String reflection = response.getContent();
            log.debug("WenshiReflectionEngineAdapter.reflect: executionId={}, reflectionLength={}",
                    executionId, reflection != null ? reflection.length() : 0);
            return reflection;
        } catch (Exception e) {
            log.warn("WenshiReflectionEngineAdapter.reflect failed: {}", e.getMessage());
            return "反思失败: " + e.getMessage() + "。建议人工复盘。";
        }
    }

    /**
     * 基于反思结论重规划，生成改进后的任务描述。
     */
    @Override
    public String replan(String goal, String reflection) {
        try {
            String prompt = "基于以下反思结论，改进原始目标的任务描述。只返回改进后的任务描述，不要解释。\n"
                    + "原始目标：" + goal + "\n"
                    + "反思结论：" + (reflection != null ? reflection.substring(0, Math.min(500, reflection.length())) : "");
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(defaultLlmModel)
                    .messages(List.of(
                            Message.builder().role("system").content("你是任务规划专家。").build(),
                            Message.builder().role("user").content(prompt).build()))
                    .temperature(0.3)
                    .maxTokens(256)
                    .stream(false)
                    .build();
            LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
            LlmResponse response = client.chat(llmRequest);
            String improved = response.getContent();
            log.debug("WenshiReflectionEngineAdapter.replan: goal={}, improvedLength={}", goal, improved.length());
            return improved;
        } catch (Exception e) {
            log.warn("WenshiReflectionEngineAdapter.replan failed, returning original goal: {}", e.getMessage());
            return goal;
        }
    }
}
