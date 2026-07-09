package com.gewu.application.agent;

import com.gewu.application.agent.dto.PermissionResult;
import com.gewu.application.agent.dto.ToolResult;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.audit.AuditLogService;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {

    private static final int MAX_OUTPUT_SIZE = 10 * 1024;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final AgentToolMapper agentToolMapper;
    private final ToolSchemaValidator schemaValidator;
    private final PermissionEvaluationService permissionService;
    private final AuditLogService auditLogService;

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
            output = executeViaHttp(tool, arguments, context);
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
        int timeoutMs = context.getTimeout() > 0 ? context.getTimeout() * 1000 : tool.getTimeoutMs();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tool.getEndpoint()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(arguments))
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.of(ResultCode.AGENT_TIMEOUT, "工具执行超时");
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "HTTP 调用失败: " + e.getMessage());
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