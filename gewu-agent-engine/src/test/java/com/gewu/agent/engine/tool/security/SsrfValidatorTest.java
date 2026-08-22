package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.AgentEngineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SsrfValidator} 单元测试。
 * <p>通过注入 HostResolver 替代真实 DNS 解析，验证白名单与内网封禁规则。
 */
@DisplayName("SSRF 校验器")
class SsrfValidatorTest {

    /** 返回固定地址的解析器（免真实 DNS） */
    private SsrfValidator validatorWithResolver(String allowedHosts, String resolvedIp) {
        SsrfValidator validator = new SsrfValidator(allowedHosts);
        validator.setHostResolver(host -> {
            if ("UNRESOLVABLE".equals(host)) {
                throw new UnknownHostException(host);
            }
            return InetAddress.getByName(resolvedIp);
        });
        return validator;
    }

    @Test
    @DisplayName("白名单为空时默认拒绝全部主机")
    void emptyWhitelistDeniesAll() {
        SsrfValidator validator = new SsrfValidator("");
        assertThatThrownBy(() -> validator.validate(URI.create("https://example.com/api")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("不在白名单中");
    }

    @Test
    @DisplayName("非 http/https 协议被拦截")
    void nonHttpSchemeBlocked() {
        SsrfValidator validator = new SsrfValidator("example.com");
        assertThatThrownBy(() -> validator.validate(URI.create("ftp://example.com/file")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("不支持的协议");
        assertThatThrownBy(() -> validator.validate(URI.create("file:///etc/passwd")))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("URI 为空或无主机被拦截")
    void nullOrMissingHostBlocked() {
        SsrfValidator validator = new SsrfValidator("example.com");
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("目标地址不能为空");
        assertThatThrownBy(() -> validator.validate(URI.create("https:///no-host")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("无效的目标地址");
    }

    @Test
    @DisplayName("白名单命中且解析为公网地址时放行")
    void whitelistedPublicHostPasses() {
        SsrfValidator validator = validatorWithResolver("api.example.com", "8.8.8.8");
        assertThatCode(() -> validator.validate(URI.create("https://api.example.com/v1/data")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("解析为回环地址被拦截（即使已在白名单）")
    void loopbackResolutionBlocked() {
        SsrfValidator validator = validatorWithResolver("api.example.com", "127.0.0.1");
        assertThatThrownBy(() -> validator.validate(URI.create("https://api.example.com/x")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("禁止访问内网地址");
    }

    @Test
    @DisplayName("解析为站点本地地址（192.168.x.x）被拦截")
    void siteLocalResolutionBlocked() {
        SsrfValidator validator = validatorWithResolver("internal.example.com", "192.168.1.1");
        assertThatThrownBy(() -> validator.validate(URI.create("http://internal.example.com/x")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("禁止访问内网地址");
    }

    @Test
    @DisplayName("解析为链路本地地址（169.254.x.x）被拦截")
    void linkLocalResolutionBlocked() {
        SsrfValidator validator = validatorWithResolver("link.example.com", "169.254.1.1");
        assertThatThrownBy(() -> validator.validate(URI.create("http://link.example.com/x")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("禁止访问内网地址");
    }

    @Test
    @DisplayName("主机名无法解析被拦截")
    void unresolvableHostBlocked() {
        // UNRESOLVABLE 需在白名单中才能走到 DNS 解析步骤
        SsrfValidator validator = validatorWithResolver("UNRESOLVABLE", "8.8.8.8");
        assertThatThrownBy(() -> validator.validate(URI.create("https://UNRESOLVABLE/x")))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("无法解析目标地址");
    }

    @Test
    @DisplayName("SecurityCheck 插件路径：配置端点会被校验")
    void checkValidatesConfiguredEndpoint() {
        SsrfValidator validator = new SsrfValidator("api.example.com");
        com.gewu.agent.engine.spi.ToolConfig config = com.gewu.agent.engine.spi.ToolConfig.builder()
                .toolName("http_tool").toolType("http")
                .endpoint("https://evil.example.com/api").build();
        assertThatThrownBy(() -> validator.check("http_tool", "{}", null, config))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("不在白名单中");
        // 无端点配置时不校验
        assertThatCode(() -> validator.check("tool", "{}", null, null)).doesNotThrowAnyException();
    }
}
