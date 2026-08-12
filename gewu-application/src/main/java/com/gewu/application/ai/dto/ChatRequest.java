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
    @Builder.Default
    private boolean stream = false;
}
