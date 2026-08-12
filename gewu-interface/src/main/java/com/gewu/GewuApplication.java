package com.gewu;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 格物平台启动类.
 * <p>
 * 主 MapperScan 使用 annotationClass=Mapper.class 只扫描标注了 @Mapper 的接口，
 * 避免扫描到 com.gewu.infrastructure.mapper.wenshi 包下的接口（这些由
 * WenshiDataSourceConfig 的独立 @MapperScan 绑定到 PostgreSQL 数据源）。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.gewu")
@MapperScan(basePackages = "com.gewu.infrastructure.mapper", annotationClass = Mapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
public class GewuApplication {

    public static void main(String[] args) {
        SpringApplication.run(GewuApplication.class, args);
        System.out.println("===================================");
        System.out.println("  格物平台启动成功!");
        System.out.println("  http://localhost:8081");
        System.out.println("===================================");
    }
}