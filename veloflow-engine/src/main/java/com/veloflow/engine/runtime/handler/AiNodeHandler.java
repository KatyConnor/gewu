package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.commons.FlowTemplates;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI/集成节点 Handler 公共基类：模板渲染、结构化输出、失败安全的完成回调。
 * 各 Handler 自持 SPI 桥（FlowAiBridge/FlowDataSourceBridge/FlowMailBridge）。
 */
abstract class AiNodeHandler implements WorkflowNodeHandler {

    /** 模板渲染：${} 变量替换 + XSS 实体还原 */
    protected String render(String template, Map<String, Object> variables) {
        return FlowTemplates.render(template, variables);
    }

    /** 结构化输出（JSON 文本）：AI 节点统一输出契约 */
    protected String outputJson(LinkedHashMap<String, Object> fields) {
        return VeloflowJson.MAPPER.valueToTree(fields).toString();
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
