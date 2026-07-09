package com.gewu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 格物平台启动类.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.gewu")
@MapperScan("com.gewu.infrastructure.mapper")
public class GewuApplication {

    public static void main(String[] args) {
        SpringApplication.run(GewuApplication.class, args);
        System.out.println("===================================");
        System.out.println("  格物平台启动成功!");
        System.out.println("  http://localhost:8080");
        System.out.println("===================================");
    }
}