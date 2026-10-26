package com.gewu.interfaceapi.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 网页终端 WS 注册（浏览器侧入口，设计文档 44）。
 * <p>路径在 SecurityConfig 中 permitAll——鉴权由一次性 ticket 承担
 * （握手 URL 携带，消费即失效），而非 JWT 请求头（WS 握手无法自定义头）。
 */
@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalProxyWsHandler proxyHandler;

    public TerminalWebSocketConfig(TerminalProxyWsHandler proxyHandler) {
        this.proxyHandler = proxyHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(proxyHandler, "/api/v1/workspaces/terminal/ws")
                .setAllowedOriginPatterns("*");
    }
}
