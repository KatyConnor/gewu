package com.gewu.sandbox.template;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 沙箱模板匹配服务 — 根据语言/项目技术栈自动匹配镜像和资源配比.
 */
@Slf4j
@Component
public class SandboxTemplateMatcher {

    @Value("${gewu.sandbox.registry.url:}")
    private String registryUrl;

    @Value("${gewu.sandbox.registry.repository:gewu/sandbox}")
    private String registryRepository;

    public enum SandboxTemplate {
        PYTHON("python", "python:3.12", "Python 数据科学", 1, 2048, 10240),
        NODEJS("node", "node:20", "Node.js 全栈", 1, 2048, 10240),
        JAVA("java", "openjdk:21-slim", "Java 后端", 2, 4096, 20480),
        SHELL("shell", "alpine:3.20", "通用命令行", 0.5, 512, 5120);

        @Getter
        private final String code;
        @Getter
        private final String imageSuffix;
        @Getter
        private final String displayName;
        @Getter
        private final double defaultCpu;
        @Getter
        private final int defaultMemoryMb;
        @Getter
        private final int defaultDiskMb;

        SandboxTemplate(String code, String imageSuffix, String displayName,
                        double defaultCpu, int defaultMemoryMb, int defaultDiskMb) {
            this.code = code;
            this.imageSuffix = imageSuffix;
            this.displayName = displayName;
            this.defaultCpu = defaultCpu;
            this.defaultMemoryMb = defaultMemoryMb;
            this.defaultDiskMb = defaultDiskMb;
        }

        public static SandboxTemplate fromCode(String code) {
            for (SandboxTemplate t : values()) {
                if (t.code.equalsIgnoreCase(code)) return t;
            }
            throw BusinessException.of(ResultCode.SANDBOX_TEMPLATE_NOT_FOUND, "未知模板: " + code);
        }
    }

    public String resolveImage(String language) {
        SandboxTemplate template = resolveTemplate(language);
        return buildFullImageName(template.getImageSuffix());
    }

    public SandboxTemplate resolveTemplate(String language) {
        if (language == null || language.isBlank()) {
            return SandboxTemplate.SHELL;
        }
        String lang = language.toLowerCase().trim();
        return switch (lang) {
            case "python", "py", "python3" -> SandboxTemplate.PYTHON;
            case "javascript", "js", "typescript", "ts", "node", "nodejs" -> SandboxTemplate.NODEJS;
            case "java", "kotlin", "scala", "groovy", "jvm" -> SandboxTemplate.JAVA;
            default -> SandboxTemplate.SHELL;
        };
    }

    public SandboxTemplate resolveFromTechStack(String techStack) {
        if (techStack == null || techStack.isBlank()) {
            return SandboxTemplate.SHELL;
        }
        String lower = techStack.toLowerCase();
        if (lower.contains("python") || lower.contains("django") || lower.contains("flask")
                || lower.contains("pytorch") || lower.contains("tensorflow") || lower.contains("pandas")) {
            return SandboxTemplate.PYTHON;
        }
        if (lower.contains("node") || lower.contains("react") || lower.contains("vue")
                || lower.contains("typescript") || lower.contains("javascript") || lower.contains("next")) {
            return SandboxTemplate.NODEJS;
        }
        if (lower.contains("java") || lower.contains("spring") || lower.contains("kotlin")
                || lower.contains("maven") || lower.contains("gradle")) {
            return SandboxTemplate.JAVA;
        }
        return SandboxTemplate.SHELL;
    }

    public SandboxTemplate resolveFromTemplate(String template) {
        if (template == null || template.isBlank()) {
            return SandboxTemplate.SHELL;
        }
        return SandboxTemplate.fromCode(template);
    }

    public CreateTemplateResult createFromTemplate(SandboxTemplate template) {
        return new CreateTemplateResult(
                buildFullImageName(template.getImageSuffix()),
                (int) Math.ceil(template.getDefaultCpu()),
                template.getDefaultMemoryMb(),
                template.getDefaultDiskMb()
        );
    }

    private String buildFullImageName(String imageSuffix) {
        if (registryUrl != null && !registryUrl.isBlank()) {
            String base = registryUrl.replaceAll("/$", "");
            return base + "/" + registryRepository + "/" + imageSuffix;
        }
        return imageSuffix;
    }

    public record CreateTemplateResult(
            String image,
            int cpuCores,
            int memoryMb,
            int diskMb
    ) {}
}