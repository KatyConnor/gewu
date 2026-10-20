package com.gewu.agent.engine.core;

import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.agent.engine.llm.model.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 执行任务 - 描述一次 Agent 执行请求。
 * <p>使用方构建此对象提交给 {@link AgentEngine} 执行。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    /** Agent 标识（直接对话模式可为 null） */
    private String agentId;
    /** 会话标识 */
    private String sessionId;
    /**
     * 工作空间绑定会话标识（可选）。文件工具的绑定与历史加载解耦：
     * 非空时文件工具以该会话的 directory/sandbox 为工作空间（写入归属同会话），
     * 历史加载仍只看 {@link #sessionId}。典型用途：spawn 子代理共享父会话
     * 工作空间但不共享对话历史。为空时回退 {@link #sessionId}（兼容既有行为）。
     */
    private String workspaceSessionId;
    /** 操作用户 */
    private String userId;
    /** 用户消息 */
    private String message;
    /** 历史消息（为空时从 SessionContextService 加载） */
    private List<Message> history;
    /** LLM 供应商标识（显式指定，优先于 AgentSpec） */
    private String modelProvider;
    /** 模型名称（显式指定，优先于 AgentSpec） */
    private String modelName;
    /** Agent 模式：assistant / expert / creative / precise */
    private String agentMode;
    /** 思维模式：chain-of-thought / tree-of-thought / react / step-by-step / socratic */
    private String thinkingStyle;
    /** 采样温度（覆盖模式默认值） */
    private Double temperature;
    /** 最大 token 数（覆盖默认值） */
    private Integer maxTokens;
    /** 模型路由开关（基准评测/按需启用）：true 时忽略显式模型咨询 ModelSelector 动态改选 */
    private Boolean modelRouteEnabled;
    /** 感知引擎产出的结构化意图（运行时填充，调用方一般不设） */
    private PerceptionEngine.Intent intent;
    /** 用户套餐剩余配额（tokens，配额体系注入；null=不限制，仅受模型上下文窗口约束） */
    private Long quotaTokenBudget;
    /** 配额熔断开关（用户偏好注入；null/true=token 耗尽熔断，false=仅告警不熔断） */
    private Boolean quotaBlockEnabled;
    /** 子代理嵌套深度（0=顶层任务）：spawn_subagents 派生的子任务=父深度+1，达到上限不再注册派生工具 */
    @Builder.Default
    private int agentDepth = 0;
    /** 显式工作区根（桌面本地形态：会话绑定目录，随任务透传给子代理——工作区继承与历史共享解耦；
     *  null=引擎侧按会话 directory/全局默认解析，平台沙箱路由不受影响） */
    private String workspaceRoot;
    /** 入口调度指令内容（AGENTS.md/GEWU.md 跨层裁决产物，由运行时适配层解析填充；null=无，不注入）。
     *  引擎不感知文件系统：发现/读取/裁决一律在运行时完成（设计文档 56 §5.3） */
    private String entryDirectives;
    /** 入口调度指令来源标签（system prompt 展示用，如 project:AGENTS.md@1.3.0 / user-global:GEWU.md / none） */
    private String entryDirectivesSource;
    /** 入口调度指令元数据 JSON（source/version/reason/sha256/truncated——审计与前端展示用，引擎不解析） */
    private String entryDirectivesMeta;
}
