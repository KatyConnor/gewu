package com.gewu.desktop.llm;

import com.gewu.desktop.config.DesktopProps;
import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmProviderConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桌面本地模型供应商（D1-11）：从 local_model_provider 表读取供应商配置，
 * Key 经 {@link DesktopKeyVault} 内存解析后构造 OpenAI 兼容客户端（引擎 LlmClientRegistry 动态创建）。
 * <p>协同形态的 DesktopProxyLlmProvider（平台 LLM 代理）在 Phase 2 接入（ProviderRegistry 双源）。
 *
 * @since 1.0.0
 */
@Component
@Profile("!stub")
public class DesktopLocalLlmProvider implements LlmProvider {

    private final JdbcTemplate jdbc;
    private final DesktopKeyVault keyVault;
    private final DesktopProps props;

    public DesktopLocalLlmProvider(JdbcTemplate jdbc, DesktopKeyVault keyVault, DesktopProps props) {
        this.jdbc = jdbc;
        this.keyVault = keyVault;
        this.props = props;
        // 开发态兜底：yml 配置的 Key 注入内存保管
        keyVault.loadAll(props.getModelKeys());
    }

    @Override
    public LlmProviderConfig getProviderConfig(String providerCode) {
        List<LlmProviderConfig> found = jdbc.query(
                "SELECT code, base_url FROM local_model_provider WHERE code = ? AND enabled = 1",
                (rs, i) -> new LlmProviderConfig(rs.getString(1), keyVault.get(rs.getString(1)), rs.getString(2)),
                providerCode);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<String> listProviderCodes() {
        return jdbc.queryForList("SELECT code FROM local_model_provider WHERE enabled = 1", String.class);
    }

}
