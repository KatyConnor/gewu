package com.gewu.desktop.spike;

import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmProviderConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;

/**
 * Spike stub 集（@Profile("stub")）：开发态无真实厂商 Key 时的全链路验证桩。
 * <p>生产/默认 profile 不加载——真实供应商经 local_model_provider 表 + KeyVault 配置。
 * 同时负责 stub 环境的种子数据（模型供应商 + 默认 Agent）。
 *
 * @since 1.0.0
 */
@Configuration
@Profile("stub")
public class SpikeLlmBeans {

    @Bean(destroyMethod = "stop")
    public StubOpenAiServer stubOpenAiServer() throws IOException {
        StubOpenAiServer server = new StubOpenAiServer();
        server.start();
        return server;
    }

    @Bean
    public LlmProvider spikeLlmProvider(StubOpenAiServer stub) {
        return new LlmProvider() {
            @Override
            public LlmProviderConfig getProviderConfig(String providerCode) {
                if ("spike-stub".equals(providerCode)) {
                    return new LlmProviderConfig("spike-stub", "sk-spike", "http://127.0.0.1:" + stub.getPort() + "/v1");
                }
                return null;
            }

            @Override
            public List<String> listProviderCodes() {
                return List.of("spike-stub");
            }
        };
    }

    /** stub 环境种子：spike-stub 供应商 + 默认 Agent（幂等） */
    @Bean
    public ApplicationRunner stubSeedRunner(StubOpenAiServer stub, JdbcTemplate jdbc) {
        return (ApplicationArguments args) -> {
            long now = System.currentTimeMillis();
            Integer providerCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM local_model_provider WHERE code = 'spike-stub'", Integer.class);
            if (providerCount != null && providerCount == 0) {
                jdbc.update("""
                                INSERT INTO local_model_provider (code, display_name, base_url, models_json, enabled, created_at, updated_at)
                                VALUES ('spike-stub', 'Spike 桩模型', ?, ?, 1, ?, ?)""",
                        "http://127.0.0.1:" + stub.getPort() + "/v1",
                        "[{\"name\":\"stub-model\",\"displayName\":\"桩模型\",\"description\":\"本地 stub 验证模型\"}]",
                        now, now);
            }
            Integer agentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM local_agent WHERE id = 'desktop-assistant'", Integer.class);
            if (agentCount != null && agentCount == 0) {
                jdbc.update("""
                                INSERT INTO local_agent (id, name, description, model_provider, model_name, system_prompt, status, created_at, updated_at)
                                VALUES ('desktop-assistant', '桌面助手', '格物桌面端内置助手', 'spike-stub', 'stub-model',
                                        '你是格物桌面端智能助手，用中文简洁、准确地回答问题。', 1, ?, ?)""",
                        now, now);
            }
        };
    }
}
