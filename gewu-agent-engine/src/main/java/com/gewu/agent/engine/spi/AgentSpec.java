package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 配置规格（框架内 Agent 抽象，替代业务侧持久化实体）。
 * <p>由 {@link PersistenceService} SPI 加载，描述一个 Agent 的模型与提示词配置。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSpec {

    /** Agent 唯一标识 */
    private String id;
    /** 名称 */
    private String name;
    /** 描述 */
    private String description;
    /** LLM 供应商标识 */
    private String modelProvider;
    /** 模型名称 */
    private String modelName;
    /** 模型扩展配置（JSON，可含 sandboxEnabled / sandboxImage / maxTokens 等） */
    private String modelConfig;
    /** 系统提示词 */
    private String systemPrompt;
    /** 状态：1 启用 / 0 禁用 */
    private Integer status;
}
