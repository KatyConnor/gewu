package com.gewu.sandbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * 沙箱服务安全配置 - 内部 API-Key 服务间认证.
 * <p>沙箱服务(8082)仅被 interface 服务经 SandboxClient 内网调用，不面向浏览器。
 * 通过 X-Internal-Api-Key 请求头认证，密钥由两侧的 gewu.sandbox.internal-api-key /
 * gewu.sandbox.api.internal-key 配置（环境变量 GEWU_INTERNAL_API_KEY）共享。
 * <p>启用方法级安全：exec/executeCode 的 @PreAuthorize("hasAuthority('sandbox:manage')")
 * 依赖内部过滤器授予该权限后才会真实生效。
 * <p>prod profile 下禁止空密钥或历史弱默认密钥启动（fail-fast）；开发环境仅告警。
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SandboxSecurityConfig {

    /** 与 gewu-application SandboxClient 中的认证头保持一致 */
    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    /** 历史上硬编码在仓库中的弱默认密钥——prod profile 命中即拒绝启动 */
    private static final Set<String> WEAK_DEFAULTS = Set.of("gewu-dev-internal-key-change-me");

    private final ObjectMapper objectMapper;
    private final Environment environment;

    @Value("${gewu.sandbox.internal-api-key:}")
    private String internalApiKey;

    @PostConstruct
    void checkInternalApiKey() {
        boolean weak = internalApiKey == null || internalApiKey.isBlank()
                || WEAK_DEFAULTS.contains(internalApiKey);
        if (!weak) {
            return;
        }
        if (isProdProfile()) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 GEWU_INTERNAL_API_KEY 配置沙箱内部 API 密钥（禁止空值或历史弱默认值）");
        }
        log.warn("沙箱内部 API 密钥未配置或使用历史弱默认值，仅限本地开发环境使用");
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                return true;
            }
        }
        return false;
    }

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
                            "internal-service", null,
                            List.of(new SimpleGrantedAuthority("sandbox:manage"))));
                }
            }
            chain.doFilter(request, response);
        }
    }
}
