package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体节点（51 号 §2.5/§九，53 号 §3.4）：直调宿主 AgentExecutor（ReAct 运行时），
 * 会话 ID 填工作流实例 ID（可追溯）。输出 {output, totalTokens}。
 * <p>config：agentId（必填）、taskTemplate（必填，${} 变量模板）。
 */
@Component
public class AgentNodeHandler extends AiNodeHandler {

    private final ObjectProvider<FlowAiBridge> bridgeProvider;

    public AgentNodeHandler(ObjectProvider<FlowAiBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("agentId", "taskTemplate");
    }

    @Override
    protected String nodeLabel() {
        return "智能体调用";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowAiBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            context.complete(false, "AI 桥未接入（宿主未提供 FlowAiBridge 实现），无法执行 agent 节点");
            return;
        }
        completeSafely(context, () -> {
            String task = render(str(config, "taskTemplate"), context.variables());
            FlowAiBridge.AgentResult result = bridge.invokeAgent(new FlowAiBridge.AgentSpec(
                    str(config, "agentId"), task,
                    context.instance().getId(), context.instance().getTitle()));
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("output", result.output());
            output.put("totalTokens", result.totalTokens());
            context.complete(outputJson(output));
        });
    }
}
