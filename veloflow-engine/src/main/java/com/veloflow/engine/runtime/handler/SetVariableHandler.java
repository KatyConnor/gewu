package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变量赋值节点：variables（目标键←表达式）求值产出新变量集，输出平铺进实例变量空间。
 */
@Component
@RequiredArgsConstructor
public class SetVariableHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "set-variable";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("variables");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Object declared = context.config().get("variables");
        if (!(declared instanceof Map<?, ?> variablesMap)) {
            context.complete(false, "set-variable 节点 variables 必须是对象（键←表达式）");
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : variablesMap.entrySet()) {
            try {
                output.put(String.valueOf(entry.getKey()),
                        evaluator.evaluate(String.valueOf(entry.getValue()), context.variables()));
            } catch (IllegalArgumentException e) {
                context.complete(false, "变量 " + entry.getKey() + " 表达式求值失败: " + e.getMessage());
                return;
            }
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(entry.getKey()).append("\":")
                    .append(toJsonLiteral(entry.getValue()));
            first = false;
        }
        json.append("}");
        context.complete(json.toString());
    }

    private String toJsonLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
