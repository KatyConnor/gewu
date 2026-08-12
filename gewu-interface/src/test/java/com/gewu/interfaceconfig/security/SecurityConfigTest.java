package com.gewu.interfaceconfig.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 安全规则单元测试。
 *
 * <p>使用最小化 Spring 上下文仅加载 SecurityConfig 和测试 Controller，
 * 通过 springSecurity() 显式装配 Spring Security 过滤器链。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {SecurityConfigTest.TestConfig.class, SecurityConfigTest.TestController.class})
@WebAppConfiguration
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("认证接口允许未认证访问")
    void authEndpoints_arePermitAll() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON_UTF8)
                        .content("{\"username\":\"anyone\",\"password\":\"anypass\"}"))
                .andExpect(status().isNotFound()); // permitAll 允许到达 Controller，Controller 无映射返回 404
    }

    @Test
    @DisplayName("AI 对话接口未认证返回 401")
    void aiEndpoints_requireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat/stream")
                        .contentType(JSON_UTF8)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("模型配置接口未认证返回 401")
    void modelEndpoints_requireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/models/providers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录后可访问 AI 对话接口")
    @WithMockUser
    void aiEndpoints_areAccessibleAfterLogin() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat/stream")
                        .contentType(JSON_UTF8)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("登录后可访问模型配置列表")
    @WithMockUser
    void modelEndpoints_areAccessibleAfterLogin() throws Exception {
        mockMvc.perform(get("/api/v1/models/providers"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Actuator 在主服务端口不可公开访问")
    void actuator_notExposedOnMainPort() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Swagger 文档保持公开")
    void swaggerEndpoints_arePermitAll() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("OPTIONS 预检请求保持公开")
    void optionsRequests_arePermitAll() throws Exception {
        mockMvc.perform(options("/api/v1/ai/chat/stream")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("模型写操作普通用户返回 403")
    @WithMockUser
    void modelWrite_forbiddenForRegularUser() throws Exception {
        mockMvc.perform(post("/api/v1/models/providers")
                        .contentType(JSON_UTF8)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("模型写操作管理员可到达 Controller")
    @WithMockUser(roles = "ADMIN")
    void modelWrite_allowedForAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/models/providers")
                        .contentType(JSON_UTF8)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("方法级 @PreAuthorize 普通用户返回 403")
    @WithMockUser
    void methodLevelAdmin_forbiddenForRegularUser() throws Exception {
        mockMvc.perform(get("/test/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("方法级 @PreAuthorize 管理员可访问")
    @WithMockUser(roles = "ADMIN")
    void methodLevelAdmin_allowedForAdmin() throws Exception {
        mockMvc.perform(get("/test/admin"))
                .andExpect(status().isOk());
    }

    /**
     * 提供 SecurityConfig 所需的依赖 bean。
     */
    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter() {
            return stubChainFilter(mock(JwtAuthenticationFilter.class));
        }

        @Bean
        public XssFilter xssFilter() {
            return stubChainFilter(mock(XssFilter.class));
        }

        @Bean
        public SecurityHeadersFilter securityHeadersFilter() {
            return stubChainFilter(mock(SecurityHeadersFilter.class));
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * 让 mock Filter 在执行后把请求继续传给 FilterChain，
         * 否则请求会停在 mock 中，MockMvc 会返回默认 200。
         */
        private static <T extends jakarta.servlet.Filter> T stubChainFilter(T filter) {
            try {
                doAnswer(invocation -> {
                    ServletRequest request = invocation.getArgument(0);
                    ServletResponse response = invocation.getArgument(1);
                    FilterChain chain = invocation.getArgument(2);
                    try {
                        chain.doFilter(request, response);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }).when(filter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return filter;
        }
    }

    /**
     * 仅用于让 Spring MVC 有一个 Controller 可加载；不实现任何业务逻辑。
     */
    @org.springframework.web.bind.annotation.RestController
    static class TestController {

        @org.springframework.web.bind.annotation.GetMapping("/test/admin")
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
        public String adminOnly() {
            return "admin";
        }
    }
}
