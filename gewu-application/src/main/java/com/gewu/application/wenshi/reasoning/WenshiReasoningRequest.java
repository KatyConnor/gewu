package com.gewu.application.wenshi.reasoning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Wenshi 推理请求对象。
 * <p>
 * 封装用户输入消息、会话上下文及推理约束条件。
 * 支持通过 {@link ReasoningConstraints} 控制推理行为（最大轮次、Token 限制、功能开关等）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WenshiReasoningRequest {

    /** 代理 ID，标识当前推理会话所属的 Agent */
    private String agentId;

    /** 会话 ID，用于关联同一对话上下文中的多次推理 */
    private String sessionId;

    /** 用户 ID，用于个性化记忆检索 */
    private String userId;

    /** 租户 ID，用于多租户数据隔离 */
    private String tenantId;

    /** 用户输入的消息内容 */
    private String message;

    /** 前端选择的模型 ID（如 LongCat-2.0、qwen-plus），用于解析 LLM 提供商 */
    private String model;

    /** 关联项目 ID（项目需求会话时传入，纯对话时为 null），决定文件存储位置 */
    private String projectId;

    /** 关联需求 ID（需求文件会话时传入，可为 null） */
    private String requirementId;

    /** 扩展上下文信息，可携带业务自定义参数 */
    private Map<String, Object> context;

    /** 推理约束条件，控制推理行为边界 */
    private ReasoningConstraints constraints;

    /**
     * 推理约束条件。
     * <p>
     * 用于限制推理过程中的资源消耗和功能开关，防止无限递归或过度调用。
     * 可通过 {@link #defaults()} 获取推荐默认值。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasoningConstraints {

        /** 最大 LLM 推理轮次，防止过度调用 */
        private int maxLlmRounds;

        /** 最大工具执行轮次，防止工具调用死循环 */
        private int maxToolRounds;

        /** 最大 Token 消耗量，控制成本 */
        private int maxTokens;

        /** 是否启用结果验证（Critic） */
        private boolean enableCritic;

        /** 是否启用经验复用路由 */
        private boolean enableExperience;

        /** 是否启用网络搜索路由（WEB_SEARCH 策略及 LLM_REASONING 自动增强） */
        private boolean enableWebSearch;

        /**
         * 创建默认约束条件。
         * <p>
         * 默认值：LLM 5 轮、工具 10 轮、Token 4096、启用验证、经验复用和网络搜索。
         *
         * @return 默认约束条件实例
         * @since 1.0.0
         */
        public static ReasoningConstraints defaults() {
            return ReasoningConstraints.builder()
                    .maxLlmRounds(5)
                    .maxToolRounds(10)
                    .maxTokens(4096)
                    .enableCritic(true)
                    .enableExperience(true)
                    .enableWebSearch(true)
                    .build();
        }
    }
}
