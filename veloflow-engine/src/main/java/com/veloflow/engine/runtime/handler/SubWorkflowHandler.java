package com.veloflow.engine.runtime.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.veloflow.engine.commons.FlowTemplates;
import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.WorkflowInstanceDTO;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子工作流节点（53 号 §3.7）：等待型——激活即经宿主实例服务启动子工作流实例
 * （触发类型 MANUAL），登记 child_instance_id 后挂起；子实例成功完成以其
 * finalOutput 完成父等待行，失败/终止时父行同步失败（WorkflowEventService 唤醒）。
 * <p>防环：拒绝直接自引用；流程链深度经 __wfDepth 变量传递（>10 层拒绝）。
 * <p>子流程在启动调用线程内同步终态（纯 AUTO 链）时直接完成父行，
 * 不依赖监听器异步唤醒。
 */
@Slf4j
@Component
public class SubWorkflowHandler implements WorkflowNodeHandler {

    /** 子流程嵌套深度上限（防 A→B→A 递归实例膨胀） */
    static final int MAX_DEPTH = 10;
    static final String DEPTH_VAR = "__wfDepth";

    private final ObjectProvider<WorkflowInstanceService> instanceServiceProvider;

    public SubWorkflowHandler(ObjectProvider<WorkflowInstanceService> instanceServiceProvider) {
        this.instanceServiceProvider = instanceServiceProvider;
    }

    @Override
    public String type() {
        return "sub-workflow";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("workflowId");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        String childWorkflowId = str(config, "workflowId");
        if (childWorkflowId == null || childWorkflowId.isBlank()) {
            context.complete(false, "sub-workflow 缺少 workflowId 配置");
            return;
        }
        if (childWorkflowId.equals(context.instance().getWorkflowId())) {
            context.complete(false, "sub-workflow 不允许引用自身工作流（防自环）");
            return;
        }
        int depth = parseDepth(context.variables());
        if (depth >= MAX_DEPTH) {
            context.complete(false, "子工作流嵌套深度超限（" + MAX_DEPTH + " 层），疑似流程环");
            return;
        }
        WorkflowInstanceService instanceService = instanceServiceProvider.getIfAvailable();
        if (instanceService == null) {
            context.complete(false, "实例服务不可用，无法启动子工作流");
            return;
        }
        String input = FlowTemplates.render(str(config, "inputTemplate"), context.variables());
        Map<String, Object> childVars = new HashMap<>(context.variables());
        childVars.put(DEPTH_VAR, depth + 1);
        try {
            WorkflowInstanceDTO child = instanceService.startByTrigger(childWorkflowId,
                    context.instance().getInitiatorId(),
                    "子流程 ← " + context.instance().getTitle(),
                    VeloflowJson.MAPPER.writeValueAsString(childVars), "MANUAL");
            // 登记子实例 ID（异步唤醒锚点）；同步终态时直接驱动父行
            context.nodeInstance().setChildInstanceId(child.getInstanceId());
            // start() 返回的 DTO 是启动线程的内存快照：子流程若在启动链内同步失败，
            // failInstance 改写的是 onCompletion 重查的 DB 对象，快照仍显示 running——
            // 此处按 DB 重查真实终态，失败/完成直接驱动父行（同步链内完成）；
            // 仍在途则挂起，由 WorkflowEventService 监听子实例终态唤醒（child_instance_id 已落库）
            WorkflowInstanceDTO childNow = instanceService.getInstance(child.getInstanceId());
            String childStatus = childNow.getStatus();
            log.info("sub-workflow 子实例状态: child={}, status={}, parent={}",
                    child.getInstanceId(), childStatus, context.instance().getId());
            if ("completed".equals(childStatus)) {
                context.complete(childNow.getFinalOutput() == null ? "" : childNow.getFinalOutput());
            } else if ("failed".equals(childStatus) || "terminated".equals(childStatus)) {
                context.complete(false, "子流程已" + ("failed".equals(childStatus) ? "失败" : "终止")
                        + ": " + child.getInstanceId());
            }
            // 其余状态（running/waiting）：保持挂起，由 WorkflowEventService 监听子实例终态唤醒
        } catch (Exception e) {
            context.complete(false, "子流程启动失败: " + e.getMessage());
        }
    }

    private int parseDepth(Map<String, Object> variables) {
        Object depth = variables.get(DEPTH_VAR);
        if (depth instanceof Number n) {
            return n.intValue();
        }
        if (depth != null) {
            try {
                return Integer.parseInt(String.valueOf(depth));
            } catch (NumberFormatException ignored) {
                // 非法值按 0 层
            }
        }
        return 0;
    }

    static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
