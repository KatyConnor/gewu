package com.gewu.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 后台管理服务（独立部署）：系统管理（用户/角色/权限/菜单/机构/配额套餐/技能审核）
 * 与沙箱安全审计，独立端口 8083 提供服务。
 * <p>仅依赖 common/domain/infrastructure（共库直查），不依赖主应用 application 与引擎；
 * 认证与主应用同源（同一 JWT_SECRET 签发的 token，Redis 黑名单共享）。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.gewu")
@MapperScan("com.gewu.infrastructure.mapper")
@EnableScheduling
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
        System.out.println("===================================");
        System.out.println("  格物平台 - 后台管理服务启动成功!");
        System.out.println("  http://localhost:8083");
        System.out.println("===================================");
    }
}
