package com.gewu.application.workflow.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.workflow.engine.WorkflowExpressionEvaluator;
import com.gewu.application.workflow.engine.WorkflowNodeContext;
import com.gewu.application.workflow.engine.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据转换节点：mapping（目标字段←源表达式）逐字段求值产出新对象（结构化渲染，防注入）。
 */
@Component
@RequiredArgsConstructor
public class TransformHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return "transform";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("mapping");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Object mapping = context.config().get("mapping");
        if (!(mapping instanceof Map<?, ?> mappingMap)) {
            context.complete(false, "transform 节点 mapping 必须是对象");
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mappingMap.entrySet()) {
            String targetKey = String.valueOf(entry.getKey());
            try {
                output.put(targetKey, evaluator.evaluate(String.valueOf(entry.getValue()), context.variables()));
            } catch (IllegalArgumentException e) {
                context.complete(false, "字段 " + targetKey + " 表达式求值失败: " + e.getMessage());
                return;
            }
        }
        try {
            context.complete(objectMapper.writeValueAsString(output));
        } catch (Exception e) {
            context.complete(false, "转换结果序列化失败: " + e.getMessage());
        }
    }
}
