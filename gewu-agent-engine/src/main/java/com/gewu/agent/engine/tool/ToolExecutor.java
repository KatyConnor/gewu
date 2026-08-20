package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.mcp.McpServerManager;
import com.gewu.agent.engine.mcp.McpToolResult;
import com.gewu.agent.engine.spi.AuditService;
import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.PermissionResult;
import com.gewu.agent.engine.spi.PermissionService;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.security.SecurityChain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 工具执行器 - 统一调度工具执行，内置安全管线。
 * <p>执行流程：
 * <ol>
 *   <li>{@link SecurityChain} 安全检查（Schema 校验 / 提示注入检测 / SSRF / 代码扫描统一调度）</li>
 *   <li>{@link PermissionService} 权限评估</li>
 *   <li>执行：代码工具 / HTTP / MCP / 沙箱</li>
 *   <li>输出截断</li>
 *   <li>{@link AuditService} 审计记录</li>
 * </ol>
 *
 * @since 1.0.0
 */
@Slf4j
public class ToolExecutor {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ToolRegistry registry;
    private final SecurityChain securityChain;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SandboxExecutor sandboxExecutor;
    private final McpServerManager mcpServerManager;
    private final ObjectMapper objectMapper;
    private final int maxOutputSize;
    private final int maxRedirects;
    private final int defaultTimeoutSeconds;

    public ToolExecutor(ToolRegistry registry, SecurityChain securityChain,
                        PermissionService permissionService, AuditService auditService,
                        SandboxExecutor sandboxExecutor, McpServerManager mcpServerManager,
                        ObjectMapper objectMapper,
                        int maxOutputSize, int maxRedirects, int defaultTimeoutSeconds) {
        this.registry = registry;
        this.securityChain = securityChain;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.sandboxExecutor = sandboxExecutor;
        this.mcpServerManager = mcpServerManager;
        this.objectMapper = objectMapper;
        this.maxOutputSize = maxOutputSize;
        this.maxRedirects = maxRedirects;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    /**
     * 执行工具。
     *
     * @param toolName  工具名
     * @param arguments 参数（JSON 字符串）
     * @param context   执行上下文
     * @param config    工具配置（代码工具可为 null）
     * @return 执行结果
     */
    public ToolResult execute(String toolName, String arguments, ToolContext context, ToolConfig config) {
        long startTime = System.currentTimeMillis();

        // 1. 安全检查（参数 schema 校验等）
        securityChain.check(toolName, arguments, context, config);

        // 2. 权限评估
        PermissionResult permission = permissionService.evaluate(context.getAgentId(), toolName, null);
        if ("deny".equals(permission.getEffect())) {
            return failAndAudit(startTime, toolName, context, "权限拒绝: " + permission.getReason());
        }
        if (permission.isRequireApproval()) {
            return failAndAudit(startTime, toolName, context, "需要用户确认: " + permission.getReason());
        }

        // 3. 执行
        String output;
        try {
            Tool codeTool = registry.getTool(toolName);
            if (codeTool != null) {
                // 代码注册工具优先
                ToolResult result = codeTool.invoke(arguments, context);
                long duration = System.currentTimeMillis() - startTime;
                auditService.recordToolExecution(context.getUserId(), context.getAgentId(),
                        toolName, result.isSuccess(), duration);
                return result;
            }

            if (config == null) {
                throw AgentEngineException.of("TOOL_NOT_FOUND", "工具不存在且无配置: " + toolName);
            }

            String type = config.getToolType();
            if ("mcp".equals(type)) {
                output = executeViaMcp(config, arguments);
            } else if (context.isSandboxEnabled() && "code_execute".equals(type)) {
                output = executeInSandbox(config, arguments, context);
            } else {
                output = executeViaHttp(config, arguments, context);
            }
        } catch (AgentEngineException e) {
            return failAndAudit(startTime, toolName, context, e.getMessage());
        } catch (Exception e) {
            log.error("工具执行失败: tool={}", toolName, e);
            return failAndAudit(startTime, toolName, context, "执行失败: " + e.getMessage());
        }

        // 4. 输出截断
        long duration = System.currentTimeMillis() - startTime;
        boolean truncated = false;
        if (output != null && output.length() > maxOutputSize) {
            output = output.substring(0, maxOutputSize);
            truncated = true;
        }

        // 5. 审计
        auditService.recordToolExecution(context.getUserId(), context.getAgentId(), toolName, true, duration);

        return ToolResult.builder()
                .success(true)
                .output(output)
                .duration(duration)
                .truncated(truncated)
                .build();
    }

    private String executeViaHttp(ToolConfig tool, String arguments, ToolContext context) {
        if (tool.getEndpoint() == null || tool.getEndpoint().isBlank()) {
            throw AgentEngineException.of("TOOL_CONFIG_INVALID", "工具未配置执行端点");
        }
        URI uri = URI.create(tool.getEndpoint());
        securityChain.validateUri(uri);
        int timeoutMs = context.getTimeout() > 0 ? context.getTimeout() * 1000
                : (tool.getTimeoutMs() != null ? tool.getTimeoutMs() : defaultTimeoutSeconds * 1000);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(arguments))
                .build();
        try {
            HttpResponse<String> response = sendWithRedirectLimit(request, 0);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AgentEngineException.of("TOOL_TIMEOUT", "工具执行超时");
        } catch (AgentEngineException e) {
            throw e;
        } catch (Exception e) {
            throw AgentEngineException.of("TOOL_EXECUTION_FAILED", "HTTP 调用失败: " + e.getMessage());
        }
    }

    private HttpResponse<String> sendWithRedirectLimit(HttpRequest request, int redirectCount) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (isRedirect(response.statusCode())) {
            if (redirectCount >= maxRedirects) {
                throw AgentEngineException.of("TOOL_EXECUTION_FAILED", "重定向次数超过上限: " + maxRedirects);
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> AgentEngineException.of("TOOL_EXECUTION_FAILED", "重定向响应缺少 Location"));
            URI redirectUri = request.uri().resolve(location);
            securityChain.validateUri(redirectUri);
            HttpRequest redirectRequest = HttpRequest.newBuilder()
                    .uri(redirectUri)
                    .timeout(request.timeout().orElse(Duration.ofSeconds(30)))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            return sendWithRedirectLimit(redirectRequest, redirectCount + 1);
        }
        return response;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private String executeViaMcp(ToolConfig tool, String arguments) {
        if (tool.getMcpServerId() == null || tool.getMcpServerId().isBlank()) {
            throw AgentEngineException.of("TOOL_CONFIG_INVALID", "MCP 工具未配置 Server ID");
        }
        McpToolResult result = mcpServerManager.getOrConnect(tool.getMcpServerId())
                .callTool(tool.getToolName(), arguments);
        if (!result.isSuccess()) {
            throw AgentEngineException.of("TOOL_EXECUTION_FAILED",
                    result.getError() != null ? result.getError() : "MCP 工具执行失败");
        }
        return result.getOutput();
    }

    private String executeInSandbox(ToolConfig tool, String arguments, ToolContext context) {
        String language;
        String code;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            language = node.has("language") ? node.get("language").asText() : "python";
            code = node.has("code") ? node.get("code").asText()
                    : node.has("command") ? node.get("command").asText() : arguments;
        } catch (Exception e) {
            language = "shell";
            code = arguments;
        }

        // 危险操作扫描已前移至 SecurityChain（CodeScannerCheck）
        int timeout = context.getTimeout() > 0 ? context.getTimeout() : defaultTimeoutSeconds;
        ExecResult resp = sandboxExecutor.executeCode(language, code, timeout);
        String stdout = resp.getStdout() != null ? resp.getStdout() : "";
        String stderr = resp.getStderr() != null ? resp.getStderr() : "";
        return stdout + (stderr.isEmpty() ? "" : "\n[stderr] " + stderr);
    }

    private ToolResult failAndAudit(long startTime, String toolName, ToolContext context, String error) {
        long duration = System.currentTimeMillis() - startTime;
        auditService.recordToolExecution(context.getUserId(), context.getAgentId(), toolName, false, duration);
        return ToolResult.failure(error, duration);
    }
}
