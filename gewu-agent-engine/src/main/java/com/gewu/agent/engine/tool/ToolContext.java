package com.gewu.agent.engine.tool;

import com.gewu.agent.engine.core.event.AgentEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具执行上下文 - 携带执行时环境信息。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolContext {

    /** 操作用户 */
    private String userId;
    /** 会话标识 */
    private String sessionId;
    /** Agent 标识 */
    private String agentId;
    /** 执行超时（秒） */
    private int timeout;
    /** 是否启用沙箱 */
    private boolean sandboxEnabled;
    /** 沙箱镜像 */
    private String sandboxImage;
    /** 项目标识 */
    private String projectId;
    /** 项目沙箱 ID（S9 F3 文件工具路由目标，null=用户默认工作空间） */
    private String sandboxId;
    /** 工作空间根目录（S9 F3 文件工具相对路径基准） */
    private String workspaceRoot;
    /** 任务计划状态（S9 F5）：内置 plan_task 工具写入，done 事件快照回传 */
    @Builder.Default
    private PlanState planState = new PlanState();
    /** 子代理派生上下文（spawn_subagents）：当前任务嵌套深度（0=顶层） */
    @Builder.Default
    private int agentDepth = 0;
    /** 子代理派生上下文：父任务已解析的 LLM 供应商（子任务模型回退用） */
    private String modelProvider;
    /** 子代理派生上下文：父任务已解析的模型名（子任务模型回退用） */
    private String modelName;
    /** 子代理派生上下文：父任务配额 token 上限（继承传递，null=不限制） */
    private Long quotaTokenBudget;
    /** 子代理派生上下文：父任务配额熔断开关（继承传递） */
    private Boolean quotaBlockEnabled;
    /** 子代理派生上下文：父任务入口调度指令（AGENTS.md/GEWU.md 裁决产物，子任务继承不重复解析） */
    private String entryDirectives;
    /** 子代理派生上下文：父任务入口调度指令来源标签 */
    private String entryDirectivesSource;
    /** 子代理派生上下文：父任务入口调度指令元数据 JSON */
    private String entryDirectivesMeta;
    /** 子目录嵌套入口已服务集合（设计文档 57）：本轮任务内已附加过的子目录（去重） */
    @Builder.Default
    private java.util.Set<String> nestedEntriesServed = new java.util.HashSet<>();
    /** 子目录嵌套探针记忆化（设计文档 57 性能修复）：key=dir/name，值=内容，空串=已探测但缺失 */
    @Builder.Default
    private java.util.Map<String, String> nestedEntryProbeCache = new java.util.HashMap<>();

    /**
     * 任务计划状态（S9 F5）：跨工具轮次持有最新任务清单，
     * 由内置 plan_task 工具更新、DONE 事件快照透出。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanState {
        /** 计划标题 */
        private volatile String title = "";
        /** 步骤列表（全量覆盖语义） */
        private volatile List<AgentEvent.PlanStepInfo> steps = new CopyOnWriteArrayList<>();
        /** 计划文件相对路径（markdown 参数提交时由引擎写入工作空间 plan/ 目录） */
        private volatile String planPath;
    }
}
