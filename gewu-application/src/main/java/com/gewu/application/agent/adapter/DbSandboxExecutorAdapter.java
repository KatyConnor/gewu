package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link SandboxExecutor} 业务适配 - 桥接框架与现有 {@link SandboxClient}。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbSandboxExecutorAdapter implements SandboxExecutor {

    private final SandboxClient sandboxClient;
    private final SessionMapper sessionMapper;

    @Override
    public ExecResult executeCode(String language, String code, int timeoutSeconds) {
        return executeCode(null, language, code, timeoutSeconds);
    }

    /**
     * 带会话上下文执行：按 sessionId 定位会话工作空间并挂载到执行容器的 /workspace，
     * 使代码执行期间写入的文件落在用户持久卷、可被会话文件工具读取。
     * 会话缺失/未绑定工作空间时退化为无卷的隔离执行（不阻塞工具调用）。
     */
    @Override
    public ExecResult executeCode(ToolContext context, String language, String code, int timeoutSeconds) {
        String workspaceId = resolveWorkspaceId(context);
        ExecCommandResponse resp = sandboxClient.executeCode(language, code, timeoutSeconds, workspaceId);
        return ExecResult.builder()
                .stdout(resp.getStdout())
                .stderr(resp.getStderr())
                .exitCode(resp.getExitCode() != null ? resp.getExitCode() : 0)
                .success(resp.getExitCode() == null || resp.getExitCode() == 0)
                .build();
    }

    private String resolveWorkspaceId(ToolContext context) {
        try {
            if (context == null || context.getSessionId() == null || context.getSessionId().isBlank()) {
                return null;
            }
            Session session = sessionMapper.selectById(context.getSessionId());
            return session != null ? session.getWorkspaceId() : null;
        } catch (Exception e) {
            // 工作空间解析失败不阻塞代码执行：降级为无卷隔离执行
            log.warn("解析会话工作空间失败, sessionId={}, 降级为隔离执行: {}",
                    context != null ? context.getSessionId() : null, e.getMessage());
            return null;
        }
    }
}
