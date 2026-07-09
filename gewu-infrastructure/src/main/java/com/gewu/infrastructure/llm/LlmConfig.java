package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Configuration
public class LlmConfig {

    @Value("${gewu.ai.qwen.api-key:}")
    private String qwenApiKey;

    @Value("${gewu.ai.qwen.base-url:https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation}")
    private String qwenBaseUrl;

    @Value("${gewu.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${gewu.ai.deepseek.base-url:https://api.deepseek.com/v1/chat/completions}")
    private String deepseekBaseUrl;

    @Bean
    public HttpClient llmHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public ObjectMapper llmObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public QwenClient qwenClient(HttpClient llmHttpClient, ObjectMapper llmObjectMapper) {
        return new QwenClient(qwenApiKey, qwenBaseUrl, llmObjectMapper, llmHttpClient);
    }

    @Bean
    public DeepSeekClient deepSeekClient(HttpClient llmHttpClient, ObjectMapper llmObjectMapper) {
        return new DeepSeekClient(deepseekApiKey, deepseekBaseUrl, llmObjectMapper, llmHttpClient);
    }

    @Bean
    public Map<String, LlmClient> llmClientMap(QwenClient qwenClient, DeepSeekClient deepSeekClient) {
        return Map.of(
                "qwen", qwenClient,
                "deepseek", deepSeekClient
        );
    }
}
