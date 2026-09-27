package com.gewu.application.workflow.engine.handler;

import com.gewu.application.workflow.engine.WorkflowExpressionEvaluator;
import com.gewu.application.workflow.engine.WorkflowNodeContext;
import com.gewu.application.workflow.engine.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 条件判断节点（51 号 §二 2.3）：求值表达式输出 matched=true/false，
 * 调度器按出边 label 匹配路由（true/false 双出口）。
 */
@Component
@RequiredArgsConstructor
public class ConditionHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "condition";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("expression");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        String expression = String.valueOf(context.config().getOrDefault("expression", "true"));
        boolean matched;
        try {
            matched = evaluator.evaluateBoolean(expression, context.variables());
        } catch (IllegalArgumentException e) {
            context.complete(false, "条件表达式求值失败: " + e.getMessage());
            return;
        }
        context.complete(toJson("matched", String.valueOf(matched)));
    }

    static String toJson(String key, String value) {
        return "{\"" + key + "\": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
