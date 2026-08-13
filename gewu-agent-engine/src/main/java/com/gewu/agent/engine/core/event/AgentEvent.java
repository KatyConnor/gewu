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
}
