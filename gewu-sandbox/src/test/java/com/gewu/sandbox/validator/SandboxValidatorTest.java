package com.gewu.sandbox.validator;

import com.gewu.sandbox.exception.SandboxResourceLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 沙箱准入校验回归测试（P1）：镜像白名单（含 dev-base 前缀）与资源上限的服务端强制。
 */
class SandboxValidatorTest {

    private final SandboxValidator validator = new SandboxValidator();

    @Test
    @DisplayName("dev 镜像在默认白名单内")
    void acceptDevImage() {
        assertDoesNotThrow(() -> validator.validateImage("gewu/dev-base:latest"));
    }

    @Test
    @DisplayName("sandbox-base 镜像在默认白名单内")
    void acceptSandboxBaseImage() {
        assertDoesNotThrow(() -> validator.validateImage("gewu/sandbox-base:latest"));
    }

    @Test
    @DisplayName("白名单外镜像拒绝")
    void rejectUnknownImage() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> validator.validateImage("evil/image:v1"));
    }

    @Test
    @DisplayName("CPU 超上限拒绝")
    void rejectCpuOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> validator.validateResourceLimits(9, null, null, null));
    }

    @Test
    @DisplayName("内存超上限拒绝")
    void rejectMemoryOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> validator.validateResourceLimits(null, 32768, null, null));
    }

    @Test
    @DisplayName("磁盘超上限拒绝")
    void rejectDiskOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> validator.validateResourceLimits(null, null, 102400, null));
    }

    @Test
    @DisplayName("超时超上限拒绝")
    void rejectTimeoutOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> validator.validateResourceLimits(null, null, null, 90000));
    }

    @Test
    @DisplayName("dev 档资源配置通过")
    void acceptDevResourceProfile() {
        assertDoesNotThrow(() -> validator.validateResourceLimits(2, 4096, 20480, 86400));
    }
}
