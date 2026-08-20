package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全检查链 - 按序执行多个 {@link SecurityCheck}。
 * <p>框架默认装配 {@link SchemaValidator}、{@link PromptInjectionDetector}（输入安全层）、
 * {@link OutputSanitizer}（输出安全层）、{@link SsrfValidator} 与 {@link CodeScannerCheck}
 * （工具安全层）。使用方可注册自定义 SecurityCheck（如内容审核 / 敏感信息脱敏）。
 *
 * @since 1.0.0
 */
public class SecurityChain implements SecurityCheck {

    private final List<SecurityCheck> checks;

    public SecurityChain(List<SecurityCheck> checks) {
        this.checks = checks != null ? checks : new ArrayList<>();
    }

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        for (SecurityCheck check : checks) {
            check.check(toolName, arguments, context, config);
        }
    }

    /**
     * 校验目标 URI（HTTP 工具执行与重定向逐跳校验的统一入口）。
     * <p>委托链内 {@link SsrfValidator} 实例，链内无 SSRF 校验器时跳过。
     */
    public void validateUri(URI uri) {
        for (SecurityCheck check : checks) {
            if (check instanceof SsrfValidator ssrfValidator) {
                ssrfValidator.validate(uri);
            }
        }
    }

    public List<SecurityCheck> getChecks() {
        return checks;
    }
}
