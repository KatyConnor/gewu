package com.gewu.agent.engine.core.event;

/**
 * 统一事件映射器（AE-P2-1 二期切片）：跨层事件转发时的全字段拷贝工具。
 * <p>背景：编排轨 AGENT 节点/PLAN_EXECUTE 步骤把内层执行器事件转发给宿主时，
 * 各站点手工 builder 拷贝字段集不一致——PipelineModeHandler 丢 metadata（ask_user、
 * plan_* 与 budget_* 等带元数据事件到宿主即失效）即同源缺陷（RPT-002 F-01 同族）。
 * 转发一律走本工具，新增事件字段时只改此处。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public final class AgentEvents {

    private AgentEvents() {
    }

    /** 全字段拷贝 + nodeId/role 标注（编排节点/运行时步骤转发用） */
    public static AgentEvent copyWith(AgentEvent src, String nodeId, String role) {
        return AgentEvent.builder()
                .type(src.getType())
                .content(src.getContent())
                .reasoning(src.getReasoning())
                .toolCall(src.getToolCall())
                .toolResult(src.getToolResult())
                .errorMessage(src.getErrorMessage())
                .finishReason(src.getFinishReason())
                .plan(src.getPlan())
                .planTitle(src.getPlanTitle())
                .metadata(src.getMetadata())
                .nodeId(nodeId)
                .role(role)
                .build();
    }

    /** 全字段恒等拷贝（主循环身份转发用，不改动任何字段） */
    public static AgentEvent copy(AgentEvent src) {
        return copyWith(src, src.getNodeId(), src.getRole());
    }
}
