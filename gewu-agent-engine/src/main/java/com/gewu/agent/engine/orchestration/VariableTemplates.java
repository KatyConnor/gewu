package com.gewu.agent.engine.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图变量模板渲染器 - 供 TOOL 节点参数与 AGENT/PLAN 节点 inputs 共用。
 * <p>占位符语法 {@code ${变量名}}（变量名为图上下文变量，通常是上游节点 ID），
 * 未定义变量替换为空串并告警。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-09-24
 */
@Slf4j
public final class VariableTemplates {

    /** 参数模板占位符：${varName} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    /** JSON 结构化渲染共用序列化器（AE-P1-6/49 号 F-10） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private VariableTemplates() {
    }

    /**
     * 渲染模板：将 {@code ${varName}} 替换为上下文变量值（未知变量替换为空串并告警）。
     * <p>兼容降级（WFO-06）：历史写法 {@code ${var.xxx}} 按字面查不到变量时
     * 去掉 {@code var.} 前缀重查一次，命中则按 {@code ${xxx}} 解析并告警提示迁移。
     */
    public static String render(String template, OrchestrationContext ctx) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = ctx.getVariable(varName);
            if (value == null && varName.startsWith("var.")) {
                String stripped = varName.substring("var.".length());
                Object fallback = ctx.getVariable(stripped);
                if (fallback != null) {
                    log.warn("变量引用使用历史前缀写法，已降级解析（推荐写法: ${}）: ${var.}",
                            stripped, stripped);
                    value = fallback;
                }
            }
            if (value == null) {
                log.warn("模板变量未定义，替换为空串: {} (节点参数渲染)", varName);
            }
            String replacement = value != null ? String.valueOf(value) : "";
            // Matcher.quoteReplacement 防替换串中的 $/\ 破坏渲染
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 结构化 JSON 参数渲染（AE-P1-6，49 号 F-10）：TOOL 节点参数模板按 JSON 解析后，
     * 仅对字符串值做 {@code ${var}} 渲染再整体序列化——上游变量含引号/换行等 JSON 特殊字符时，
     * 值经序列化器转义，不再破坏外层 JSON 结构（纯文本替换会把原始引号直接带进字符串外导致参数碎裂）。
     * <p>非合法 JSON 模板回退 {@link #render(String, OrchestrationContext)} 纯文本渲染（旧行为兼容）。
     */
    public static String renderJsonArguments(String template, OrchestrationContext ctx) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(template);
            return JSON_MAPPER.writeValueAsString(renderNode(root, ctx));
        } catch (Exception e) {
            log.warn("TOOL 参数模板非合法 JSON，回退纯文本渲染: cause={}", e.getMessage());
            return render(template, ctx);
        }
    }

    /** 递归渲染：对象/数组容器重建，字符串值做占位符渲染，其余类型原样保留 */
    private static JsonNode renderNode(JsonNode node, OrchestrationContext ctx) {
        if (node.isObject()) {
            ObjectNode out = JSON_MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), renderNode(e.getValue(), ctx)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON_MAPPER.createArrayNode();
            node.forEach(child -> out.add(renderNode(child, ctx)));
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(render(node.textValue(), ctx));
        }
        return node;
    }
}
