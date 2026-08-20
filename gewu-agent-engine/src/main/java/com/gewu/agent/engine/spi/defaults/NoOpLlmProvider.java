package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmProviderConfig;

/**
 * {@link LlmProvider} 的 NoOp 默认实现 - 不提供动态供应商配置。
 * <p>此时仅代码注册的 {@link com.gewu.agent.engine.llm.LlmClient} 可用。
 *
 * @since 1.0.0
 */
public class NoOpLlmProvider implements LlmProvider {

    @Override
    public LlmProviderConfig getProviderConfig(String providerCode) {
        return null;
    }
}
