package com.gewu.application.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private String agentId;
    private String sessionId;
    private String message;
    /** 模型 ID（如 qwen-plus, deepseek-chat），用于选择 LLM 模型 */
    private String model;
    /** Agent 模式: assistant/expert/creative/precise */
    private String agentMode;
    /** 思维模式: chain-of-thought/tree-of-thought/react/step-by-step/socratic */
    private String thinkingStyle;
    /** 关联项目 ID（项目需求会话时传入，纯对话时为 null） */
    private String projectId;
    /** 关联需求 ID（需求文件会话时传入，可为 null） */
    private String requirementId;
    /** 客户端幂等 ID：流式接口重放/网络重试时避免重复落库，前端每次发送生成并复用 */
    private String clientId;
    /** 引擎覆盖（基准评测用）：legacy / wenshi，空=按 gewu.wenshi.routing 全局配置 */
    private String engineOverride;
    /** 模型路由开关（基准评测用）：true 时引擎按复杂度/预算动态改选模型，忽略显式 model */
    private Boolean modelRouteEnabled;
    @Builder.Default
    private boolean stream = false;
}
