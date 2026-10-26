package com.gewu.agent.engine.spi;

import com.gewu.agent.engine.tool.ToolContext;

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

    /**
     * 带会话上下文执行代码。实现方可借 context 定位会话工作空间并挂载，
     * 使执行期间写入 /workspace 的文件与会话文件工具共享同一持久视图；
     * 未覆写时退化为无上下文的隔离执行（原行为）。
     *
     * @param context        工具执行上下文（含 sessionId）
     * @param language       语言（python / shell / node / ...）
     * @param code           代码内容
     * @param timeoutSeconds 超时（秒）
     * @return 执行结果
     */
    default ExecResult executeCode(ToolContext context, String language, String code, int timeoutSeconds) {
        return executeCode(language, code, timeoutSeconds);
    }
}
