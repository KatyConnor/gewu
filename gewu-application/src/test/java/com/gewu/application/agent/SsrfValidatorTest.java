package com.gewu.application.agent;

import com.gewu.common.result.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * SsrfValidator 单元测试。
 */
class SsrfValidatorTest {

    private final SsrfValidator validator = new SsrfValidator();

    @BeforeEach
    void init() throws Exception {
        setAllowedHosts("api.example.com,httpbin.org");
    }

    private void setAllowedHosts(String value) throws Exception {
        Field field = SsrfValidator.class.getDeclaredField("allowedHostsConfig");
        field.setAccessible(true);
        field.set(validator, value);
    }

    @Test
    @DisplayName("白名单内的公网域名允许访问")
    void allowedHostPass() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8");
        validator.setHostResolver(host -> publicIp);
        
        assertDoesNotThrow(() -> validator.validate(URI.create("https://api.example.com/v1/chat")));
    }

    @Test
    @DisplayName("不在白名单的公网域名被拒绝")
    void notAllowedHostBlocked() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(URI.create("https://evil.com/api")));
        assertTrue(ex.getMessage().contains("不在白名单中"));
    }

    @Test
    @DisplayName("回环地址被拒绝")
    void loopbackBlocked() throws Exception {
        setAllowedHosts("127.0.0.1");
        InetAddress loopbackIp = InetAddress.getByName("127.0.0.1");
        validator.setHostResolver(host -> loopbackIp);
        
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(URI.create("http://127.0.0.1/admin")));
        assertTrue(ex.getMessage().contains("禁止访问内网地址"));
    }

    @Test
    @DisplayName("站点本地地址被拒绝")
    void siteLocalBlocked() throws Exception {
        setAllowedHosts("192.168.1.1");
        InetAddress siteLocalIp = InetAddress.getByName("192.168.1.1");
        validator.setHostResolver(host -> siteLocalIp);
        
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(URI.create("http://192.168.1.1/admin")));
        assertTrue(ex.getMessage().contains("禁止访问内网地址"));
    }

    @Test
    @DisplayName("非 HTTP 协议被拒绝")
    void unsupportedSchemeBlocked() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(URI.create("ftp://api.example.com/file")));
        assertTrue(ex.getMessage().contains("不支持的协议"));
    }

    @Test
    @DisplayName("空配置时默认拒绝所有外部主机")
    void emptyConfigDefaultDeny() throws Exception {
        setAllowedHosts("");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(URI.create("https://api.example.com/v1/chat")));
        assertTrue(ex.getMessage().contains("不在白名单中"));
    }
}
