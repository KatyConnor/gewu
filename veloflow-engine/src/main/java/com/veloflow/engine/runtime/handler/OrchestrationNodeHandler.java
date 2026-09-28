package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排图节点（51 号 §九互操作，53 号 §3.4）：经宿主桥同步执行已激活编排图
 * （active 校验 + 时长帽在宿主侧约束），编排执行记录 sessionId 填工作流实例 ID。
 * 输出 {status, executionId, output}。
 * <p>config：graphId（必填）、inputTemplate（${} 变量模板，渲染后作为图 input）。
 */
@Component
public class OrchestrationNodeHandler extends AiNodeHandler {

    public OrchestrationNodeHandler(ObjectProvider<FlowAiBridge> bridgeProvider) {
        super(bridgeProvider);
    }

    @Override
    public String type() {
        return "orchestration";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("graphId");
    }

    @Override
    protected String nodeLabel() {
        return "编排图调用";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowAiBridge bridge = bridge();
        if (bridge == null) {
            context.complete(false, "AI 桥未接入（宿主未提供 FlowAiBridge 实现），无法执行 orchestration 节点");
            return;
        }
        completeSafely(context, () -> {
            String input = render(str(config, "inputTemplate"), context.variables());
            FlowAiBridge.OrchestrationResult result = bridge.invokeOrchestration(
                    str(config, "graphId"), input, context.instance().getId());
            if (!"success".equalsIgnoreCase(result.status())
                    && !"completed".equalsIgnoreCase(result.status())) {
                context.complete(false, nodeLabel() + " 失败: " + result.errorMessage());
                return;
            }
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("status", result.status());
            output.put("executionId", result.executionId());
            output.put("output", result.output());
            context.complete(outputJson(output));
        });
    }
}
