package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JSON 解析节点：取指定变量/上游输出解析为结构化对象后原样输出。
 */
@Component
@RequiredArgsConstructor
public class JsonParseHandler implements WorkflowNodeHandler {


    @Override
    public String type() {
        return "json-parse";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("field");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        String field = String.valueOf(context.config().get("field"));
        Object value = context.variables().get(field);
        if (value == null) {
            value = context.upstreamOutput();
        }
        String text = String.valueOf(value == null ? "" : value);
        try {
            com.veloflow.engine.commons.VeloflowJson.MAPPER.readTree(text);
            context.complete(text);
        } catch (Exception e) {
            context.complete(false, "JSON 解析失败（字段 " + field + "）: " + e.getMessage());
        }
    }
}
