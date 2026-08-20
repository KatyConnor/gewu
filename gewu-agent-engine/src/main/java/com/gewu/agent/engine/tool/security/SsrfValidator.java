package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * SSRF 防护校验器 - HTTP 工具端点访问前校验（安全纵深五层之工具安全层）。
 * <p>默认拒绝所有外部主机，除非通过 {@code agent.engine.tool.allowed-hosts} 显式配置白名单。
 * 同时禁止访问内网地址（回环 / 站点本地 / 链路本地）。
 * <p>实现 {@link SecurityCheck} 接口，作为 SecurityChain 插件统一调度；
 * HTTP 重定向的逐跳校验经 {@link SecurityChain#validateUri(URI)} 委托回本类。
 *
 * @since 1.0.0
 */
public class SsrfValidator implements SecurityCheck {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    private final Set<String> allowedHosts;

    @FunctionalInterface
    interface HostResolver {
        InetAddress resolve(String host) throws UnknownHostException;
    }

    private HostResolver hostResolver = SsrfValidator::defaultResolve;

    public SsrfValidator(String allowedHostsConfig) {
        this.allowedHosts = (allowedHostsConfig == null || allowedHostsConfig.isBlank())
                ? Set.of()
                : Set.of(allowedHostsConfig.split(","));
    }

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        // SecurityChain 插件入口：HTTP 工具执行前校验配置端点
        if (config != null && config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            validate(URI.create(config.getEndpoint()));
        }
    }

    /** 校验目标 URI 是否允许访问 */
    public void validate(URI uri) {
        if (uri == null) {
            throw AgentEngineException.of("SSRF_BLOCKED", "目标地址不能为空");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme())) {
            throw AgentEngineException.of("SSRF_BLOCKED", "不支持的协议: " + uri.getScheme());
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw AgentEngineException.of("SSRF_BLOCKED", "无效的目标地址");
        }
        if (!allowedHosts.contains(host)) {
            throw AgentEngineException.of("SSRF_BLOCKED", "目标地址不在白名单中: " + host);
        }
        try {
            InetAddress addr = hostResolver.resolve(host);
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                throw AgentEngineException.of("SSRF_BLOCKED", "禁止访问内网地址: " + host);
            }
        } catch (UnknownHostException e) {
            throw AgentEngineException.of("SSRF_BLOCKED", "无法解析目标地址: " + host);
        }
    }

    /** 仅供测试替换 DNS 解析器 */
    void setHostResolver(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    private static InetAddress defaultResolve(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }
}
