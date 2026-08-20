package com.gewu.agent.engine.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 对话消息。
 * <p>遵循 OpenAI Chat Completions 消息格式：role 可为 system / user / assistant / tool。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    /** 角色：system / user / assistant / tool */
    private String role;
    /** 消息内容 */
    private String content;
    /** tool 角色消息关联的工具调用 ID */
    private String toolCallId;
    /** tool 角色消息关联的工具名 */
    private String name;
}
