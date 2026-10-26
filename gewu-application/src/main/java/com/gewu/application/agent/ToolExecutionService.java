package com.gewu.application.agent;

import com.gewu.application.agent.dto.PermissionResult;
import com.gewu.application.agent.dto.ToolResult;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.AgentTool;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.audit.AuditLogService;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mcp.McpServerManager;
import com.gewu.infrastructure.mcp.McpToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {

    private static final int MAX_OUTPUT_SIZE = 10 * 1024;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${gewu.tool.http.max-redirects:5}")
    private int maxRedirects;

    private final AgentToolMapper agentToolMapper;
    private final ToolSchemaValidator schemaValidator;
    private final PermissionEvaluationService permissionService;
    private final AuditLogService auditLogService;
    private final McpServerManager mcpServerManager;
    private final SsrfValidator ssrfValidator;
    private final SandboxCodeScanner sandboxCodeScanner;
    private final SandboxClient sandboxClient;
    private final SessionMapper sessionMapper;

    public ToolResult executeTool(String toolName, String arguments, ToolContext context) {
        long startTime = System.currentTimeMillis();

        AgentTool tool = agentToolMapper.selectById(toolName);
        if (tool == null) {
            throw BusinessException.of(ResultCode.TOOL_NOT_FOUND);
        }

        ToolSchemaValidator.ValidationResult validation = schemaValidator.validate(
                tool.getRequestSchema(), arguments);
        if (!validation.valid()) {
            return failResult(startTime, "参数校验失败: " + String.join(", ", validation.errors()));
        }

        PermissionResult permission = permissionService.evaluate(
                context.getAgentId(), tool.getToolName(), null);
        if ("deny".equals(permission.getEffect())) {
            return failResult(startTime, "权限拒绝: " + permission.getReason());
        }
        if (permission.isRequireApproval()) {
            return failResult(startTime, "需要用户确认: " + permission.getReason());
        }

        String output;
        try {
            if ("mcp".equals(tool.getToolType())) {
                output = executeViaMcp(tool, arguments);
            } else if (context.isSandboxEnabled() && "code_execute".equals(tool.getToolType())) {
                output = executeInSandbox(tool, arguments, context);
            } else {
                output = executeViaHttp(tool, arguments, context);
            }
        } catch (Exception e) {
            log.error("工具执行失败: tool={}", toolName, e);
            return failResult(startTime, "执行失败: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;
        boolean truncated = false;
        if (output != null && output.length() > MAX_OUTPUT_SIZE) {
            output = output.substring(0, MAX_OUTPUT_SIZE);
            truncated = true;
        }

        auditLogService.recordOperation(context.getUserId(), null,
                "TOOL_EXECUTE", "AGENT_TOOL", toolName, null, true, duration);

        return ToolResult.builder()
                .success(true)
                .output(output)
                .duration(duration)
                .truncated(truncated)
                .build();
    }

    private String executeViaHttp(AgentTool tool, String arguments, ToolContext context) {
        if (tool.getEndpoint() == null || tool.getEndpoint().isBlank()) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "工具未配置执行端点");
        }
        URI uri = URI.create(tool.getEndpoint());
        ssrfValidator.validate(uri);
        int timeoutMs = context.getTimeout() > 0 ? context.getTimeout() * 1000 : tool.getTimeoutMs();
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
            throw BusinessException.of(ResultCode.AGENT_TIMEOUT, "工具执行超时");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "HTTP 调用失败: " + e.getMessage());
        }
    }

    private HttpResponse<String> sendWithRedirectLimit(HttpRequest request, int redirectCount) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (isRedirect(response.statusCode())) {
            if (redirectCount >= maxRedirects) {
                throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED,
                        "重定向次数超过上限: " + maxRedirects);
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "重定向响应缺少 Location"));
            URI redirectUri = request.uri().resolve(location);
            ssrfValidator.validate(redirectUri);
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

    private String executeViaMcp(AgentTool tool, String arguments) {
        if (tool.getMcpServerId() == null || tool.getMcpServerId().isBlank()) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "MCP 工具未配置 Server ID");
        }
        McpToolResult result = mcpServerManager.getOrConnect(tool.getMcpServerId())
                .callTool(tool.getToolName(), arguments);
        if (!result.isSuccess()) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED,
                    result.getError() != null ? result.getError() : "MCP 工具执行失败");
        }
        return result.getOutput();
    }

    private String executeInSandbox(AgentTool tool, String arguments, ToolContext context) {
        String language;
        String code;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(arguments);
            language = node.has("language") ? node.get("language").asText() : "python";
            code = node.has("code") ? node.get("code").asText() : node.has("command") ? node.get("command").asText() : arguments;
        } catch (Exception e) {
            language = "shell";
            code = arguments;
        }

        // CR-018: 执行前扫描危险操作
        sandboxCodeScanner.scan(code, language);

        int timeout = context.getTimeout() > 0 ? context.getTimeout() : 30;

        try {
            // 携带会话工作空间卷挂载：执行期间写入 /workspace 的文件持久化，
            // 可被会话文件工具读取；解析失败降级为无卷隔离执行
            String workspaceId = resolveWorkspaceId(context);
            ExecCommandResponse resp = sandboxClient.executeCode(language, code, timeout, workspaceId);
            String stdout = resp.getStdout() != null ? resp.getStdout() : "";
            String stderr = resp.getStderr() != null ? resp.getStderr() : "";
            return stdout + (stderr.isEmpty() ? "" : "\n[stderr] " + stderr);
        } catch (BusinessException e) {
            if (e.getCode() == ResultCode.AGENT_TIMEOUT.getCode()) {
                throw e;
            }
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "沙箱调用失败: " + e.getMessage());
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "沙箱调用失败: " + e.getMessage());
        }
    }

    /** 按会话解析工作空间 ID（用于执行容器挂载持久卷）；解析失败返回 null 走隔离执行 */
    private String resolveWorkspaceId(ToolContext context) {
        try {
            if (context.getSessionId() == null || context.getSessionId().isBlank()) {
                return null;
            }
            Session session = sessionMapper.selectById(context.getSessionId());
            return session != null ? session.getWorkspaceId() : null;
        } catch (Exception e) {
            log.warn("解析会话工作空间失败, sessionId={}, 降级为隔离执行: {}",
                    context.getSessionId(), e.getMessage());
            return null;
        }
    }

    private ToolResult failResult(long startTime, String error) {
        return ToolResult.builder()
                .success(false)
                .error(error)
                .duration(System.currentTimeMillis() - startTime)
                .build();
    }
}