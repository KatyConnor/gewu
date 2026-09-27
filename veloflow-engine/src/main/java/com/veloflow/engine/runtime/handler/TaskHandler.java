package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 人工办理节点（51 号 §七，53 号 §3.2）：等待型——激活即挂起并登记超时，
 * 办理人经 completeNode 提交后推进。
 * <p>分派模型：assigneeId 精确指派（未指派 = 任意登录用户可办）；
 * assigneeRole 供角色圈定展示。超时行为 timeoutAction 由定时器扫描器驱动。
 */
@Component
public class TaskHandler implements WorkflowNodeHandler {

    @Override
    public String type() {
        return "task";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        Object assignee = config.get("assigneeId");
        if (assignee != null && !String.valueOf(assignee).isBlank()) {
            context.nodeInstance().setAssigneeId(String.valueOf(assignee));
        }
        applyTimeout(context, config);
    }

    /** 登记 timeout_at（调度器在激活尾部统一落库），由定时器按 timeoutAction 驱动 */
    static void applyTimeout(WorkflowNodeContext context, Map<String, Object> config) {
        long hours = parseLong(config.get("timeoutHours"), 0);
        if (hours <= 0) {
            return;
        }
        context.nodeInstance().setTimeoutAt(System.currentTimeMillis() + hours * 3_600_000L);
    }

    public static long parseLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非数字按缺省
            }
        }
        return defaultValue;
    }
}
