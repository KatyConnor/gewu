package com.gewu.interfaceconfig.security;

import com.gewu.common.jwt.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置 — 提供 JwtUtil Bean.
 *
 * <p>Access Token 30 分钟，Refresh Token 7 天。
 */
@Configuration
public class JwtConfig {

    @Value("${gewu.security.jwt.secret}")
    private String secret;

    @Value("${gewu.security.jwt.access-expiration:1800000}")
    private long accessExpiration;

    @Value("${gewu.security.jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil(secret, accessExpiration, refreshExpiration);
    }
}