package com.gewu.interfaceconfig;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gewuOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("格物平台 API")
                        .description("AI 驱动的智能开发协作平台 - RESTful API 文档\n\n" +
                                "## 认证方式\n" +
                                "使用 JWT Bearer Token 认证。获取令牌：\n" +
                                "1. POST /api/v1/auth/register 注册\n" +
                                "2. POST /api/v1/auth/login 登录获取 accessToken\n" +
                                "3. 在请求头添加 Authorization: Bearer {accessToken}\n\n" +
                                "## 错误码说明\n" +
                                "- 10000: 操作成功\n" +
                                "- 10xxx: 通用错误\n" +
                                "- 11xxx: 用户权限域\n" +
                                "- 12xxx: 项目管理域\n" +
                                "- 13xxx: 会话消息域\n" +
                                "- 14xxx: Agent 系统域\n" +
                                "- 15xxx: 工作流域\n" +
                                "- 16xxx: 沙箱域\n" +
                                "- 17xxx: 网关域")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("格物平台团队")
                                .email("dev@gewu.com"))
                        .license(new License()
                                .name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .in(SecurityScheme.In.HEADER)
                                        .name("Authorization")));
    }
}