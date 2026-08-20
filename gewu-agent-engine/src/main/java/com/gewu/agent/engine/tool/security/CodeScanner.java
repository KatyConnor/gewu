package com.gewu.agent.engine.tool.security;

/**
 * 代码安全扫描器 SPI - 沙箱代码执行前的危险操作扫描。
 * <p>使用方可实现此接口定制扫描规则。框架提供 {@link DefaultCodeScanner} 默认实现
 * （扫描 Python / Shell 危险命令与敏感路径访问）。
 *
 * @since 1.0.0
 */
public interface CodeScanner {

    /**
     * 扫描代码是否包含危险操作。
     *
     * @param code     代码内容
     * @param language 语言（python / shell / ...）
     * @throws com.gewu.agent.engine.AgentEngineException 当代码包含危险操作时
     */
    void scan(String code, String language);
}
