package com.gewu.interfaceconfig.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置 — JWT 无状态认证.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final XssFilter xssFilter;
    private final SecurityHeadersFilter securityHeadersFilter;
    private final ObjectMapper objectMapper;

    @Value("${gewu.security.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 仅在 REQUEST 分发时执行授权检查，不在 ASYNC/ERROR 分发时执行。
                        // SSE/Flux 端点在流结束后会触发 ASYNC dispatch，
                        // 此时 SecurityContext 已被 SecurityContextHolderFilter 清理，
                        // 若再次检查授权会抛出 AccessDeniedException + "response is already committed"。
                        .shouldFilterAllDispatcherTypes(false)
                        .requestMatchers(
                                AntPathRequestMatcher.antMatcher("/api/v1/auth/login"),
                                AntPathRequestMatcher.antMatcher("/api/v1/auth/register"),
                                AntPathRequestMatcher.antMatcher("/api/v1/auth/refresh")
                        ).permitAll()
                        .requestMatchers(
                                AntPathRequestMatcher.antMatcher("/swagger-ui/**"),
                                AntPathRequestMatcher.antMatcher("/v3/api-docs/**"),
                                AntPathRequestMatcher.antMatcher("/swagger-ui.html")
                        ).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).denyAll()
                        // 管理员专属：模型配置与 MCP Server 的写操作
                        .requestMatchers(adminOnlyModelMatchers()).hasRole("ADMIN")
                        .requestMatchers(adminOnlyMcpMatchers()).hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Result.fail(ResultCode.UNAUTHORIZED)));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Result.fail(ResultCode.FORBIDDEN)));
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(securityHeadersFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(xssFilter, SecurityHeadersFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Sm3PasswordEncoder();
    }

    private static AntPathRequestMatcher[] adminOnlyModelMatchers() {
        return new AntPathRequestMatcher[]{
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/models/providers"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/v1/models/providers/**"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/models/providers/**/toggle"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/v1/models/providers/**"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/models"),
                AntPathRequestMatcher.antMatcher(HttpMethod.PUT, "/api/v1/models/**"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/models/**/toggle"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/v1/models/**")
        };
    }

    private static AntPathRequestMatcher[] adminOnlyMcpMatchers() {
        return new AntPathRequestMatcher[]{
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/mcp-servers"),
                AntPathRequestMatcher.antMatcher(HttpMethod.DELETE, "/api/v1/mcp-servers/**"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/mcp-servers/**/activate"),
                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/mcp-servers/**/deactivate")
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(resolveAllowedOriginPatterns());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 必须显式列出允许的请求头：当 allowCredentials=true 时，
        // CORS 规范禁止 Access-Control-Allow-Headers 使用 "*"，
        // 否则浏览器会静默丢弃 Authorization 头，导致 JWT 认证失败。
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> resolveAllowedOriginPatterns() {
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            return Arrays.asList(allowedOrigins.split(","));
        }
        // 默认仅允许本地开发域名；生产环境必须通过环境变量显式配置
        return List.of(
                "http://localhost:*",
                "https://localhost:*",
                "http://127.0.0.1:*",
                "https://127.0.0.1:*"
        );
    }
}
