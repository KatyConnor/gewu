package com.gewu.admin.config;

import com.gewu.common.jwt.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置 — 与主应用同源（同一 JWT_SECRET 签发/校验，登录在主应用完成）。
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
