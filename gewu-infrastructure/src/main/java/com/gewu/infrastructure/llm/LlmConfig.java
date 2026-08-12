package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * LLM 客户端配置 — 注册专用客户端（qwen、deepseek）。
 * <p>
 * 其他供应商（zhipu、doubao、LongCat 等）由 LlmClientFactory 在运行时
 * 从数据库动态加载并创建 OpenAiCompatibleClient。
 *
 * @since 1.0.0
 */
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
                .followRedirects(HttpClient.Redirect.NORMAL) // 自动跟随 301/302 重定向
                .build();
    }

    @Bean
    public ObjectMapper llmObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public QwenClient qwenClient(HttpClient llmHttpClient, ObjectMapper llmObjectMapper,
                                  LlmRequestBodyBuilder bodyBuilder) {
        return new QwenClient(qwenApiKey, qwenBaseUrl, llmObjectMapper, llmHttpClient, bodyBuilder);
    }

    @Bean
    public DeepSeekClient deepSeekClient(HttpClient llmHttpClient, ObjectMapper llmObjectMapper,
                                          LlmRequestBodyBuilder bodyBuilder) {
        return new DeepSeekClient(deepseekApiKey, deepseekBaseUrl, llmObjectMapper, llmHttpClient, bodyBuilder);
    }

    /**
     * 静态注册的专用客户端 Map — 仅包含 qwen 和 deepseek。
     * 其他供应商由 LlmClientFactory 在运行时从数据库动态创建。
     */
    @Bean
    public Map<String, LlmClient> staticClientMap(QwenClient qwenClient, DeepSeekClient deepSeekClient) {
        return Map.of(
                "qwen", qwenClient,
                "deepseek", deepSeekClient
        );
    }
}