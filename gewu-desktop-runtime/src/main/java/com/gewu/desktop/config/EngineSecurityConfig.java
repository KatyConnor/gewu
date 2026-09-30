package com.gewu.desktop.config;

import com.gewu.desktop.security.EngineTokenFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 引擎安全与跨域配置（§11.1）。
 * <p><b>关键教训（实测踩坑）</b>：CORS 必须在过滤器层先于令牌校验处理——
 * 若 401 响应不带 CORS 头，浏览器会将令牌失效误报为 "Failed to fetch"（网络层错误），
 * 用户无法看到真实原因。故注册顺序：CorsFilter(0) → EngineTokenFilter(1)。
 * 引擎仅绑定 127.0.0.1 且受临时令牌保护，CORS 放开以支持渲染层直连。
 *
 * @since 1.0.0
 */
@Configuration
public class EngineSecurityConfig {

    @Bean
    public CorsConfigurationSource engineCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** CORS 过滤器（order 0）：所有响应（含 401/错误）都带 CORS 头 */
    @Bean
    public FilterRegistrationBean<CorsFilter> engineCorsFilter() {
        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(engineCorsConfigurationSource()));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(0);
        return reg;
    }

    /** 令牌过滤器（order 1，在 CORS 之后）：401 响应可被浏览器正常读取 */
    @Bean
    public FilterRegistrationBean<EngineTokenFilter> engineTokenFilter() {
        FilterRegistrationBean<EngineTokenFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new EngineTokenFilter(System.getenv("GEWU_ENGINE_TOKEN")));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }
}
