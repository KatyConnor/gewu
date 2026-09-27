package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多路分支节点：求值表达式输出 matchedCase 值，调度器按出边 label（case 值/default）路由。
 */
@Component
@RequiredArgsConstructor
public class SwitchHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "switch";
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
        Object value;
        try {
            value = evaluator.evaluate(String.valueOf(context.config().getOrDefault("expression", "")),
                    context.variables());
        } catch (IllegalArgumentException e) {
            context.complete(false, "switch 表达式求值失败: " + e.getMessage());
            return;
        }
        String matchedCase = value == null ? "default" : String.valueOf(value);
        context.complete(ConditionHandler.toJson("matched", matchedCase));
    }
}
