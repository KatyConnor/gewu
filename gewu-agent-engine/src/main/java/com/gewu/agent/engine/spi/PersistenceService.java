package com.gewu.agent.engine.spi;

import java.util.List;

/**
 * 持久化服务 SPI - Agent 配置、执行记录、工具配置的读写。
 * <p>使用方实现此接口，桥接框架与业务侧数据库（MyBatis / JPA / 任意存储）。
 * 框架提供 {@code NoOpPersistenceService} 默认实现（内存 Map），保证零业务实现也可运行。
 *
 * @since 1.0.0
 */
public interface PersistenceService {

    /** 加载 Agent 配置，不存在返回 null */
    AgentSpec loadAgent(String agentId);

    /** 加载 Agent 绑定的启用工具配置列表 */
    List<ToolConfig> loadAgentTools(String agentId);

    /** 创建执行记录，返回带 id 的记录 */
    ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input);

    /** 标记执行完成 */
    void completeExecution(String executionId, String output, Integer tokensUsed);

    /** 标记执行失败 */
    void failExecution(String executionId, String errorMessage);

    /** 查询执行记录 */
    ExecutionRecord getExecution(String executionId);
}
