package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 门控过滤节点（53 号 §3.3）：自动型——expression 求值布尔，
 * 输出 matched=pass/block，调度器按出边 label 路由（与 condition 同构）。
 * <p>block 分支未连线时调度器报"无可命中出边"→ 实例失败（onError 语义兜底）；
 * 建议将 block 边指向 error-end 或替代路径。
 */
@Component
public class FilterHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    public FilterHandler(WorkflowExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return "filter";
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
            context.complete(false, "过滤表达式求值失败: " + e.getMessage());
            return;
        }
        context.complete(ConditionHandler.toJson("matched", matched ? "pass" : "block"));
    }
}
