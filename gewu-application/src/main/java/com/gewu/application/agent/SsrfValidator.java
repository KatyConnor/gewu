package com.gewu.application.agent;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * SSRF 防护校验器 — 用于 Agent 工具 HTTP 端点访问前校验。
 *
 * <p>默认拒绝所有外部主机，除非通过 {@code gewu.tool.http.allowed-hosts} 显式配置白名单。
 * 同时禁止访问内网地址（回环、站点本地、链路本地）。
 */
@Component
public class SsrfValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    @Value("${gewu.tool.http.allowed-hosts:}")
    private String allowedHostsConfig;

    @FunctionalInterface
    interface HostResolver {
        InetAddress resolve(String host) throws UnknownHostException;
    }

    private HostResolver hostResolver = SsrfValidator::defaultResolve;

    /**
     * 校验目标 URI 是否允许访问。
     *
     * @param uri 待访问的 URI
     * @throws BusinessException 当协议、目标地址或白名单校验失败时
     */
    public void validate(URI uri) {
        if (uri == null) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "目标地址不能为空");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme())) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "不支持的协议: " + uri.getScheme());
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "无效的目标地址");
        }
        Set<String> allowed = resolveAllowedHosts();
        if (!allowed.contains(host)) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "目标地址不在白名单中: " + host);
        }
        try {
            InetAddress addr = hostResolver.resolve(host);
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "禁止访问内网地址: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "无法解析目标地址: " + host);
        }
    }

    /**
     * 仅供测试替换 DNS 解析器。
     */
    void setHostResolver(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    private static InetAddress defaultResolve(String host) throws java.net.UnknownHostException {
        return InetAddress.getByName(host);
    }

    private Set<String> resolveAllowedHosts() {
        if (allowedHostsConfig == null || allowedHostsConfig.isBlank()) {
            // 默认拒绝所有外部 HTTP 端点，防止 SSRF；用户必须显式配置白名单
            return Set.of();
        }
        return Set.of(allowedHostsConfig.split(","));
    }
}
