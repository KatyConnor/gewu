package com.gewu.sandbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 沙箱服务安全配置 - 内部 API-Key 服务间认证.
 * <p>沙箱服务(8082)仅被 interface 服务(8081)经 SandboxClient 内网调用，不面向浏览器。
 * 通过 X-Internal-Api-Key 请求头认证，密钥由两侧的 gewu.sandbox.internal-api-key /
 * gewu.sandbox.api.internal-key 配置（环境变量 GEWU_INTERNAL_API_KEY）共享。
 * 显式声明本配置以替代 classpath 上 spring-boot-starter-security 的默认全拦截策略
 * （默认策略会导致无凭证的服务间调用全部返回 401）。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SandboxSecurityConfig {

    /** 与 gewu-application SandboxClient 中的认证头保持一致 */
    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final ObjectMapper objectMapper;

    @Value("${gewu.sandbox.internal-api-key:}")
    private String internalApiKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                AntPathRequestMatcher.antMatcher("/swagger-ui/**"),
                                AntPathRequestMatcher.antMatcher("/v3/api-docs/**"),
                                AntPathRequestMatcher.antMatcher("/swagger-ui.html")
                        ).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/api/**")).authenticated()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Result.fail(ResultCode.UNAUTHORIZED, "沙箱服务内部认证失败")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Result.fail(ResultCode.FORBIDDEN)));
                        })
                )
                .addFilterBefore(new InternalApiKeyFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 校验 X-Internal-Api-Key 请求头，通过后写入无状态认证信息供授权检查使用.
     */
    private class InternalApiKeyFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String key = request.getHeader(INTERNAL_API_KEY_HEADER);
                if (internalApiKey != null && !internalApiKey.isEmpty() && key != null
                        && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                                internalApiKey.getBytes(StandardCharsets.UTF_8))) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            "internal-service", null, List.of()));
                }
            }
            chain.doFilter(request, response);
        }
    }
}
