package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link SandboxExecutor} 业务适配 - 桥接框架与现有 {@link SandboxClient}。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbSandboxExecutorAdapter implements SandboxExecutor {

    private final SandboxClient sandboxClient;

    @Override
    public ExecResult executeCode(String language, String code, int timeoutSeconds) {
        ExecCommandResponse resp = sandboxClient.executeCode(language, code, timeoutSeconds);
        return ExecResult.builder()
                .stdout(resp.getStdout())
                .stderr(resp.getStderr())
                .exitCode(resp.getExitCode() != null ? resp.getExitCode() : 0)
                .success(resp.getExitCode() == null || resp.getExitCode() == 0)
                .build();
    }
}