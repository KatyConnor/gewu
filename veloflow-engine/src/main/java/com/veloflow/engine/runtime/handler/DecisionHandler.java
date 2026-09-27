package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 决策表节点（53 号 §3.3，Business Rule Task 对位）：
 * config.decisions = [ {"when": 表达式, "value": 结果}, ... ]，按序求值首个命中的 value 输出；
 * 无命中输出 config.default 值。替代多级 switch 嵌套的行式规则表达。
 */
@Component
@RequiredArgsConstructor
public class DecisionHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "decision";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Object decisions = context.config().get("decisions");
        if (!(decisions instanceof List<?> rules) || rules.isEmpty()) {
            context.complete(context.config().getOrDefault("default", "") == null
                    ? "" : String.valueOf(context.config().get("default")));
            return;
        }
        for (Object ruleObj : rules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) {
                continue;
            }
            boolean hit;
            try {
                hit = evaluator.evaluateBoolean(String.valueOf(rule.get("when")), context.variables());
            } catch (IllegalArgumentException e) {
                context.complete(false, "决策表条件求值失败: " + e.getMessage());
                return;
            }
            if (hit) {
                context.complete(com.veloflow.engine.commons.VeloflowJson.MAPPER.valueToTree(
                        rule.get("value")).toString());
                return;
            }
        }
        Object fallback = context.config().get("default");
        context.complete(fallback != null ? com.veloflow.engine.commons.VeloflowJson.MAPPER.valueToTree(fallback).toString() : "");
    }
}
