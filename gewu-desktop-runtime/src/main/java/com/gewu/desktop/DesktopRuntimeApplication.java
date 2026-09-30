package com.gewu.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 桌面端 Agent 运行时入口（Phase 1 MVP）。
 * <p>gewu-agent-engine 以 Spring Boot 自动装配形态嵌入，桌面 SPI 适配集覆盖 NoOp 默认实现
 * （ADR-002B 一引擎两形态）：H2 持久化 / 会话上下文 / 本地模型供应商 / KeyVault。
 *
 * @since 1.0.0
 */
@SpringBootApplication
public class DesktopRuntimeApplication {

    public static void main(String[] args) throws IOException {
        // H2 文件库父目录需预先存在（支持 --gewu.desktop.data-dir=... 覆盖）
        String dataDir = Arrays.stream(args)
                .filter(a -> a.startsWith("--gewu.desktop.data-dir="))
                .map(a -> a.substring("--gewu.desktop.data-dir=".length()))
                .findFirst()
                .orElse("./data");
        Files.createDirectories(Path.of(dataDir));
        SpringApplication.run(DesktopRuntimeApplication.class, args);
    }
}

