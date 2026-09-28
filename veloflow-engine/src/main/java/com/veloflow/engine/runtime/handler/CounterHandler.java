package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数器节点（53 号 §3.7，P2 可选 → P6 二期落地）：实例变量空间计数——
 * 读变量 key（缺省 0）→ 加 step（表达式求值，可负数递减）→ 写回变量空间
 * （输出平铺机制天然落库，实例隔离天然防串）。config.reset=true 时先归零再计。
 * <p>config：key（必填）、step（表达式，默认 1）、reset（true=先归零）。
 */
@Component
@RequiredArgsConstructor
public class CounterHandler implements WorkflowNodeHandler {

    private final WorkflowExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "counter";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("key");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        try {
            String key = String.valueOf(config.getOrDefault("key", "")).trim();
            boolean reset = Boolean.parseBoolean(String.valueOf(config.getOrDefault("reset", "false")));
            Object stepValue = evaluator.evaluate(
                    String.valueOf(config.getOrDefault("step", "1")), context.variables());
            long step = stepValue instanceof Number n ? n.longValue()
                    : TaskHandler.parseLong(stepValue, 1);
            long current = reset ? 0
                    : (context.variables().get(key) instanceof Number n ? n.longValue()
                        : TaskHandler.parseLong(context.variables().get(key), 0));
            long value = current + step;
            // 输出以动态键平铺进变量空间（节点输出同形），实例隔离天然防串
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put(key, value);
            context.complete(VeloflowJson.MAPPER.valueToTree(output).toString());
        } catch (IllegalArgumentException e) {
            context.complete(false, "计数值表达式求值失败: " + e.getMessage());
        }
    }
}
