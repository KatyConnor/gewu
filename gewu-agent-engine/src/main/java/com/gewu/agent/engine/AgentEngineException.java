package com.gewu.agent.engine;

/**
 * Agent 引擎统一异常。
 * <p>框架内部所有可预期错误均抛出此异常，携带错误码与消息，便于使用方捕获与转换。
 *
 * @since 1.0.0
 */
public class AgentEngineException extends RuntimeException {

    private final String code;

    public AgentEngineException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentEngineException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 错误码，如 AGENT_NOT_FOUND / TOOL_NOT_FOUND / EXECUTION_FAILED / TOOL_ROUNDS_EXCEEDED */
    public String getCode() {
        return code;
    }

    public static AgentEngineException of(String code, String message) {
        return new AgentEngineException(code, message);
    }

    public static AgentEngineException of(String code, String message, Throwable cause) {
        return new AgentEngineException(code, message, cause);
    }
}
