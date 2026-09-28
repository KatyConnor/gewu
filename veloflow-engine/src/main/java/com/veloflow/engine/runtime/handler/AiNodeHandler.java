package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.commons.FlowTemplates;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 节点 Handler 公共基类（51 号 §2.5，P4）：经 {@link FlowAiBridge} SPI 调用宿主
 * AI 能力；桥未接入/调用失败一律以可读错误完成节点（实例 failed，onError 语义兜底）。
 */
abstract class AiNodeHandler implements WorkflowNodeHandler {

    protected final ObjectProvider<FlowAiBridge> bridgeProvider;

    protected AiNodeHandler(ObjectProvider<FlowAiBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    /** 取已接入的桥（未接入返回 null，Handler 据此完成失败） */
    protected FlowAiBridge bridge() {
        return bridgeProvider.getIfAvailable();
    }

    /** 模板渲染：${} 变量替换 + XSS 实体还原 */
    protected String render(String template, Map<String, Object> variables) {
        return FlowTemplates.render(template, variables);
    }

    /** 结构化输出（JSON 文本）：AI 节点统一输出契约 */
    protected String outputJson(LinkedHashMap<String, Object> fields) {
        return com.veloflow.engine.commons.VeloflowJson.MAPPER.valueToTree(fields).toString();
    }

    /** 节点完成（失败安全）：异常转可读失败，不中断调度 */
    protected void completeSafely(WorkflowNodeContext context, Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            context.complete(false, nodeLabel() + " 执行失败: " + e.getMessage());
        }
    }

    protected abstract String nodeLabel();

    /** config 取串（null 安全） */
    static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
