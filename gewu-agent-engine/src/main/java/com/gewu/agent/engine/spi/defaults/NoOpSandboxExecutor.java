package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.SandboxExecutor;

/**
 * {@link SandboxExecutor} 的 NoOp 默认实现 - 不支持沙箱执行。
 * <p>使用方必须提供沙箱实现才能使用 {@code code_execute} 类型工具。
 *
 * @since 1.0.0
 */
public class NoOpSandboxExecutor implements SandboxExecutor {

    @Override
    public ExecResult executeCode(String language, String code, int timeoutSeconds) {
        throw AgentEngineException.of("SANDBOX_NOT_CONFIGURED",
                "未配置沙箱执行器，请实现 SandboxExecutor SPI 以支持 code_execute 工具");
    }
}
