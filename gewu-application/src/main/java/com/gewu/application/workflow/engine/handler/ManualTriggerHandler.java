package com.gewu.application.workflow.engine.handler;

import com.gewu.application.workflow.engine.WorkflowNodeContext;
import com.gewu.application.workflow.engine.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

/**
 * 手动触发节点（51 号 §二 2.1）：正常由调度器在实例启动时直接完成（输出 trigger 变量），
 * 本 Handler 仅作类型注册与异常激活兜底。
 */
@Component
public class ManualTriggerHandler implements WorkflowNodeHandler {

    @Override
    public String type() {
        return "manual-trigger";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        context.complete(String.valueOf(context.variables().getOrDefault("trigger", "")));
    }
}
