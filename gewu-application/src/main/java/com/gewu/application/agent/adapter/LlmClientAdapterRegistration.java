package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 将现有 infrastructure 层 LLM 客户端（qwen / deepseek）包装注册为框架 {@link LlmClient} Bean。
 * <p>当 {@code agent.engine.adapter.enabled=true} 时生效，
 * 框架 {@code LlmClientRegistry} 会自动收集这些 Bean 作为静态注册客户端。
 *
 * @since 1.0.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class LlmClientAdapterRegistration {

    @Bean
    public LlmClient qwenEngineClient(com.gewu.infrastructure.llm.QwenClient qwenClient) {
        log.info("注册 qwen 客户端到 Agent 引擎");
        return new LegacyLlmClientAdapter(qwenClient);
    }

    @Bean
    public LlmClient deepseekEngineClient(com.gewu.infrastructure.llm.DeepSeekClient deepSeekClient) {
        log.info("注册 deepseek 客户端到 Agent 引擎");
        return new LegacyLlmClientAdapter(deepSeekClient);
    }
}