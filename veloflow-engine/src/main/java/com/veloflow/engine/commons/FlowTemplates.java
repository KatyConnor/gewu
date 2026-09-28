package com.veloflow.engine.commons;

import java.util.Map;

/**
 * 变量模板渲染（51 号 §2.5 P4）：`${varName}` 占位符替换，缺失变量渲染为空串。
 * <p>与 HttpRequestHandler.render 同语义：先对称还原 CR-019 XSS 层中和的
 * <code>&gt;/&lt;</code> 实体，再做变量替换。供 llm/agent/orchestration/knowledge
 * 等 AI 节点的 prompt/task/query/input 模板复用。
 *
 * @since 1.0.0
 */
public final class FlowTemplates {

    private FlowTemplates() {
    }

    /** 渲染模板：还原 HTML 实体 + ${} 变量替换 */
    public static String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        template = template.replace("&gt;", ">").replace("&lt;", "<");
        if (!template.contains("${")) {
            return template;
        }
        StringBuilder rendered = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            if (start < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            int end = template.indexOf('}', start);
            if (end < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, start);
            String varName = template.substring(start + 2, end);
            Object value = variables.get(varName);
            rendered.append(value != null ? String.valueOf(value) : "");
            cursor = end + 1;
        }
        return rendered.toString();
    }
}
