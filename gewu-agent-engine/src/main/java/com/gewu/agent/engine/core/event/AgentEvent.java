package com.gewu.agent.engine.core.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Agent 流式事件 - 引擎执行过程中产生的增量事件。
 * <p>ReAct 执行器事件类型：
 * <ul>
 *   <li>{@code status} - 状态提示（正在思考...）</li>
 *   <li>{@code thinking} - 推理/思考内容增量</li>
 *   <li>{@code content} - 正式回复内容增量</li>
 *   <li>{@code tool_call} - 工具调用决策</li>
 *   <li>{@code tool_executing} - 工具执行中</li>
 *   <li>{@code tool_result} - 工具执行结果</li>
 *   <li>{@code done} - 执行完成</li>
 *   <li>{@code error} - 执行错误</li>
 * </ul>
 * 编排层扩展事件（Phase 2）：{@code graph_start}/{@code node_start}/{@code node_complete}/
 * {@code handoff}/{@code approval_required}/{@code graph_complete} 等，通过 {@link #nodeId} 与 {@link #metadata} 携带。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    /** 事件类型 */
    private String type;
    /** 文本内容（status / content 事件） */
    private String content;
    /** 推理内容（thinking 事件） */
    private String reasoning;
    /** 工具调用信息（tool_call / tool_executing 事件） */
    private ToolCallInfo toolCall;
    /** 工具结果信息（tool_result 事件） */
    private ToolResultInfo toolResult;
    /** 错误信息（error 事件） */
    private String errorMessage;

    /** 编排层扩展：节点 ID */
    private String nodeId;
    /** 编排层扩展：角色编码 */
    private String role;
    /** 编排层扩展：附加元数据 */
    private Map<String, Object> metadata;
    /** LLM 完成原因（done 事件透传：stop 正常结束 / length 截断，前端据此提示不完整） */
    private String finishReason;
    /** 任务计划标题（plan_created / plan_updated / done 事件携带，S9 F5） */
    private String planTitle;
    /** 任务计划步骤列表（plan_created / plan_updated / done 事件携带，S9 F5） */
    private java.util.List<PlanStepInfo> plan;

    /** 任务计划步骤（F5）：模型经内置 plan_task 工具提交的结构化任务清单 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanStepInfo {
        private String id;
        private String text;
        /** pending / in_progress / done */
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        private String id;
        private String name;
        private String arguments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResultInfo {
        private String toolCallId;
        private String name;
        private String result;
    }

    // ===== 事件类型常量 =====
    public static final String STATUS = "status";
    public static final String THINKING = "thinking";
    public static final String CONTENT = "content";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_EXECUTING = "tool_executing";
    public static final String TOOL_RESULT = "tool_result";
    public static final String DONE = "done";
    public static final String ERROR = "error";
    /** 截断重试前置事件：通知调用方清空已累积的部分正文（S9，随重试整体重新生成） */
    public static final String CONTENT_RESET = "content_reset";

    // ===== 任务计划事件（S9 F5：模型经内置 plan_task 工具自主维护任务清单） =====
    /** 任务计划创建 */
    public static final String PLAN_CREATED = "plan_created";
    /** 任务计划更新（状态流转/增删步骤） */
    public static final String PLAN_UPDATED = "plan_updated";

    // ===== 阶段二扩展事件类型 =====
    /** 预算告警（消耗达 70%/90%） */
    public static final String BUDGET_WARNING = "budget_warning";
    /** 预算熔断（消耗达 100%） */
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    /** 置信度门控评估 */
    public static final String CONFIDENCE_CHECK = "confidence_check";
    /** 验证结果（双闭环验证） */
    public static final String VERIFICATION_RESULT = "verification_result";
    /** 反思洞察 */
    public static final String REFLECTION = "reflection";
    /** 经验写入记忆 */
    public static final String EXPERIENCE_SAVED = "experience_saved";
    /** 失败案例记录 */
    public static final String FAILURE_RECORDED = "failure_recorded";

    // ===== 编排层事件类型（原字面量提取为常量，T3.1） =====
    /** 编排图开始（metadata: executionId/mode） */
    public static final String GRAPH_START = "graph_start";
    /** 编排节点开始（nodeId/role） */
    public static final String NODE_START = "node_start";
    /** 编排节点完成（nodeId，metadata: outputLen） */
    public static final String NODE_COMPLETE = "node_complete";
    /** 编排图完成（metadata: status=SUCCESS/FAILED/PAUSED/CANCELLED，output） */
    public static final String GRAPH_COMPLETE = "graph_complete";
    /** Swarm 控制权移交（metadata: from/to） */
    public static final String HANDOFF = "handoff";
    /** 需要人工审批（metadata: approvalId/timeoutSeconds） */
    public static final String APPROVAL_REQUIRED = "approval_required";
    /** 审批结果（metadata: approved/operator/comment） */
    public static final String APPROVAL_RESULT = "approval_result";
    /** 编排消息信封（Supervisor 模式 TASK_DELEGATE/TASK_RESULT） */
    public static final String MESSAGE = "message";
    /** 自主目标开始 */
    public static final String GOAL_START = "goal_start";
    /** 自主目标分解完成 */
    public static final String GOAL_DECOMPOSED = "goal_decomposed";
    /** 自主目标完成（metadata: status=SUCCESS/FAILED） */
    public static final String GOAL_COMPLETE = "goal_complete";
    /** Agent 实例超时（心跳/全局） */
    public static final String AGENT_TIMEOUT = "agent_timeout";
    /** Agent 实例死锁（等待环） */
    public static final String AGENT_DEADLOCK = "agent_deadlock";
    /** 执行已暂停（graph_complete PAUSED 伴随事件） */
    public static final String EXECUTION_PAUSED = "execution_paused";
    /** 执行已取消 */
    public static final String EXECUTION_CANCELLED = "execution_cancelled";

    /** 上下文变量名：断点续跑的恢复起始节点 ID（Pipeline 暂停检查点机制） */
    public static final String VAR_RESUME_FROM_NODE = "__resumeFromNode";
}
