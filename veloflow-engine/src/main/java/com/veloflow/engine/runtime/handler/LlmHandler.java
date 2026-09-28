package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用节点（51 号 §2.5，53 号 §3.4）：同步直调宿主 LLM 客户端栈，
 * 输出 {content, model, totalTokens}——totalTokens 供实例级预算记账（51 号 §九红线）。
 * <p>config：modelProvider（必填）、modelName、promptTemplate（必填，${} 变量模板）、
 * temperature、maxTokens。
 */
@Component
public class LlmHandler extends AiNodeHandler {

    public LlmHandler(ObjectProvider<FlowAiBridge> bridgeProvider) {
        super(bridgeProvider);
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("modelProvider", "promptTemplate");
    }

    @Override
    protected String nodeLabel() {
        return "LLM 调用";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowAiBridge bridge = bridge();
        if (bridge == null) {
            context.complete(false, "AI 桥未接入（宿主未提供 FlowAiBridge 实现），无法执行 LLM 节点");
            return;
        }
        completeSafely(context, () -> {
            String prompt = render(str(config, "promptTemplate"), context.variables());
            FlowAiBridge.LlmResult result = bridge.invokeLlm(new FlowAiBridge.LlmSpec(
                    str(config, "modelProvider"),
                    str(config, "modelName"),
                    prompt,
                    config.get("temperature") instanceof Number n ? n.doubleValue() : null,
                    config.get("maxTokens") instanceof Number n ? n.intValue() : null));
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("content", result.content());
            output.put("model", result.model());
            output.put("totalTokens", result.totalTokens());
            context.complete(outputJson(output));
        });
    }
}
