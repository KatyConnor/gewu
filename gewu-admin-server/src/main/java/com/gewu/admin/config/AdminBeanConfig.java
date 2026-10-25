package com.gewu.admin.config;

import com.gewu.common.crypto.ApiKeyCryptoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 管理端显式注册的 common 包组件.
 *
 * <p>主类扫描范围仅 com.gewu.admin，gewu-common 的 @Component 不在扫描面内；
 * 需要的组件在此显式注册（与 interface 上下文同一份配置密钥 gewu.crypto.api-key-secret）。
 */
@Configuration
public class AdminBeanConfig {

    @Bean
    public ApiKeyCryptoService apiKeyCryptoService(Environment environment) {
        return new ApiKeyCryptoService(environment);
    }
}
