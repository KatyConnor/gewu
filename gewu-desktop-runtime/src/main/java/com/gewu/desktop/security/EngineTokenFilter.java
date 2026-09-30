package com.gewu.desktop.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 引擎临时令牌过滤器（§5.2/§11.1）：主进程 spawn 时经环境变量注入随机令牌，
 * 渲染层经 preload 获得后以 X-Engine-Token 携带——防止本机其他进程未授权访问引擎端口。
 * <p>/api/v1/engine/health 豁免（EngineSupervisor 心跳依赖）；未配置令牌（开发直启）放行。
 *
 * @since 1.0.0
 */
public class EngineTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Engine-Token";
    private static final String HEALTH_PATH = "/api/v1/engine/health";

    private final String expectedToken;

    public EngineTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // CORS 预检自应答：浏览器预检（OPTIONS + Origin）不携带自定义令牌头，
        // 必须在令牌校验之前应答（否则预检 401 → 渲染层 fetch 抛 Failed to fetch）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) && request.getHeader("Origin") != null) {
            response.setStatus(200);
            response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"));
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "*");
            response.setHeader("Access-Control-Max-Age", "3600");
            return;
        }
        String token = expectedToken;
        if (token == null || token.isBlank()) {
            // 开发直启模式（无令牌引导）：放行
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path.equals(HEALTH_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (token.equals(provided)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":10003,\"message\":\"引擎令牌缺失或无效\",\"data\":null}");
    }
}
