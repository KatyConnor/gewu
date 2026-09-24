package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图变量模板渲染器 - 供 TOOL 节点参数与 AGENT/PLAN 节点 inputs 共用。
 * <p>占位符语法 {@code ${变量名}}（变量名为图上下文变量，通常是上游节点 ID），
 * 未定义变量替换为空串并告警。
 *
 * @since 1.0.0
 */
@Slf4j
public final class VariableTemplates {

    /** 参数模板占位符：${varName} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

    private VariableTemplates() {
    }

    /**
     * 渲染模板：将 {@code ${varName}} 替换为上下文变量值（未知变量替换为空串并告警）。
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
}
