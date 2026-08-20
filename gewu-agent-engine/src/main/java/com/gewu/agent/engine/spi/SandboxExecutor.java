package com.gewu.agent.engine.spi;

/**
 * 沙箱执行器 SPI - 代码沙箱执行后端。
 * <p>使用方实现此接口，对接 Docker / Firecracker / gVisor 等沙箱后端，
 * 为 {@code code_execute} 类型工具提供隔离的代码执行环境。
 * 框架提供 {@code NoOpSandboxExecutor} 默认实现（抛出不支持）。
 *
 * @since 1.0.0
 */
public interface SandboxExecutor {

    /**
     * 在沙箱中执行代码。
     *
     * @param language       语言（python / shell / node / ...）
     * @param code           代码内容
     * @param timeoutSeconds 超时（秒）
     * @return 执行结果
     */
    ExecResult executeCode(String language, String code, int timeoutSeconds);
}
