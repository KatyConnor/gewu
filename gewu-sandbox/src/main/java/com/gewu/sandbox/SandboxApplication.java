package com.gewu.sandbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.gewu")
@MapperScan({"com.gewu.infrastructure.mapper", "com.gewu.sandbox.mapper"})
public class SandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
        System.out.println("===================================");
        System.out.println("  格物平台 - 沙箱服务启动成功!");
        System.out.println("  http://localhost:8082");
        System.out.println("===================================");
    }
}
