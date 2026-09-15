package com.gewu.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * LLM 客户端配置 — 基础 HTTP/JSON 设施。
 * <p>
 * 所有供应商（含 qwen、deepseek）由 LlmClientFactory 在运行时
 * 从数据库动态加载并创建 OpenAiCompatibleClient（S9 收敛，移除
 * dashscope 原生协议的旧静态客户端）。
 *
 * @since 1.0.0
 */
@Configuration
public class LlmConfig {

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

    // S9：移除 qwen/deepseek 专用静态客户端注册。
    // 旧 QwenClient 使用 dashscope 原生协议（input/messages 请求体），
    // 与 model_provider 现行 OpenAI 兼容端点（compatible-mode）协议不匹配，
    // 会静默返回空流；且静态客户端优先于 DB 动态加载，覆盖了正确配置。
    // 现全部供应商统一经 LlmClientFactory 从 DB 动态创建 OpenAiCompatibleClient。

    /**
     * 静态注册的专用客户端 Map — 已清空（S9 起）。
     * 所有供应商由 LlmClientFactory 在运行时从数据库动态创建。
     */
    @Bean
    public Map<String, LlmClient> staticClientMap() {
        return Map.of();
    }
}