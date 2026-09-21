package com.gewu.sandbox.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * exec 退出码语义回归测试（P0-3）：
 * 超时/中断固定返回 124（GNU timeout 惯例），不再落入"默认 0"导致调用方无法区分超时与成功。
 */
class DockerSandboxProviderExitCodeTest {

    @Test
    @DisplayName("命令完成后返回 inspect 真实退出码")
    void realExitCodeWhenCompleted() {
        assertEquals(1, DockerSandboxProvider.resolveExitCode(true, 1));
    }

    @Test
    @DisplayName("完成但 inspect 失败时按 0 处理")
    void defaultZeroWhenInspectMissing() {
        assertEquals(0, DockerSandboxProvider.resolveExitCode(true, null));
    }

    @Test
    @DisplayName("超时返回 124")
    void timeoutReturns124() {
        assertEquals(124, DockerSandboxProvider.resolveExitCode(false, null));
    }

    @Test
    @DisplayName("超时时忽略 inspect 结果")
    void timeoutIgnoresInspect() {
        assertEquals(124, DockerSandboxProvider.resolveExitCode(false, 0));
    }
}
