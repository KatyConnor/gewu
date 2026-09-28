package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点（51 号 §2.5 "Wenshi 检索适配"，53 号 §3.4）：经宿主桥检索
 * （知识库/搜索源由宿主实现决定），输出 {chunks: [...], source}。
 * <p>config：knowledgeBaseId（必填）、queryTemplate（必填，${} 变量模板）、topK（默认 5）。
 */
@Component
public class KnowledgeNodeHandler extends AiNodeHandler {

    private final ObjectProvider<FlowAiBridge> bridgeProvider;

    public KnowledgeNodeHandler(ObjectProvider<FlowAiBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    @Override
    public String type() {
        return "knowledge";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("knowledgeBaseId", "queryTemplate");
    }

    @Override
    protected String nodeLabel() {
        return "知识检索";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowAiBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            context.complete(false, "AI 桥未接入（宿主未提供 FlowAiBridge 实现），无法执行 knowledge 节点");
            return;
        }
        completeSafely(context, () -> {
            String query = render(str(config, "queryTemplate"), context.variables());
            int topK = (int) TaskHandler.parseLong(config.get("topK"), 5);
            FlowAiBridge.KnowledgeResult result = bridge.searchKnowledge(
                    str(config, "knowledgeBaseId"), query, topK);
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (FlowAiBridge.KnowledgeChunk chunk : result.chunks()) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("title", chunk.title());
                item.put("snippet", chunk.snippet());
                item.put("source", chunk.source());
                chunks.add(item);
            }
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("chunks", chunks);
            output.put("source", result.sourceSummary());
            context.complete(outputJson(output));
        });
    }
}
