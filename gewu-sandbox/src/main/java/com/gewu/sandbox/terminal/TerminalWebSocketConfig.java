package com.gewu.sandbox.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 网页终端 WS 注册（设计文档 44）。
 * <p>路径位于 /api/** 之下——握手请求经既有 InternalApiKeyFilter 认证
 * （调用方为 gewu-interface 反向代理，非浏览器直连）。
 */
@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWsHandler terminalWsHandler;

    public TerminalWebSocketConfig(TerminalWsHandler terminalWsHandler) {
        this.terminalWsHandler = terminalWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWsHandler, "/api/v1/sandboxes/ws/pty")
                .setAllowedOriginPatterns("*");
    }
}
