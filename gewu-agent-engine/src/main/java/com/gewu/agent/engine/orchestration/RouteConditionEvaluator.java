package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 路由条件求值器 - ROUTER 节点的出边条件求值（T3.1）。
 * <p>支持的条件语法（自研受限表达式，不接任意 EL，防注入）：
 * <ul>
 *   <li>{@code var:name == 'value'} - 变量等值比较（数字按数值比较）</li>
 *   <li>{@code var:name != 'value'} - 不等</li>
 *   <li>{@code var:name contains 'sub'} - 包含子串</li>
 *   <li>{@code var:name} - 变量存在且非空即为真</li>
 *   <li>{@code else} 或 null - 默认路由（兜底）</li>
 * </ul>
 * 求值顺序：非默认条件按出边声明顺序短路命中；均未命中时回退默认边。
 *
 * @since 1.0.0
 */
@Slf4j
public class RouteConditionEvaluator {

    /**
     * 从 ROUTER 节点的出边中选择首条命中的边。
     *
     * @param edges 出边列表（按声明顺序）
     * @param ctx   图上下文
     * @return 命中的边；无任何命中（含无默认边）返回 null
     */
    public GraphEdge selectEdge(List<GraphEdge> edges, OrchestrationContext ctx) {
        if (edges == null || edges.isEmpty()) {
            return null;
        }
        // 第一轮：非默认条件短路命中
        for (GraphEdge edge : edges) {
            String condition = edge.getCondition();
            if (condition == null || condition.isBlank() || "else".equalsIgnoreCase(condition.trim())) {
                continue;
            }
            if (evaluate(condition, ctx)) {
                log.debug("路由命中: condition={}, to={}", condition, edge.getToNode());
                return edge;
            }
        }
        // 第二轮：默认边（无条件或 else）兜底
        for (GraphEdge edge : edges) {
            String condition = edge.getCondition();
            if (condition == null || condition.isBlank() || "else".equalsIgnoreCase(condition.trim())) {
                return edge;
            }
        }
        return null;
    }

    /**
     * 求值单个条件表达式。
     */
    public boolean evaluate(String condition, OrchestrationContext ctx) {
        if (condition == null) {
            return false;
        }
        String expr = condition.trim();
        int eqIdx = indexOfOperator(expr, "==");
        int neIdx = indexOfOperator(expr, "!=");
        int containsIdx = expr.indexOf("contains");
        if (eqIdx >= 0) {
            return compare(extractVarName(expr.substring(0, eqIdx)), unquote(expr.substring(eqIdx + 2).trim()), ctx, true);
        }
        if (neIdx >= 0) {
            return compare(extractVarName(expr.substring(0, neIdx)), unquote(expr.substring(neIdx + 2).trim()), ctx, false);
        }
        if (containsIdx > 0) {
            String varName = extractVarName(expr.substring(0, containsIdx));
            String expected = unquote(expr.substring(containsIdx + "contains".length()).trim());
            Object value = ctx.getVariable(varName);
            return value != null && String.valueOf(value).contains(expected);
        }
        // 裸变量：存在且非空即真
        if (expr.startsWith("var:")) {
            Object value = ctx.getVariable(expr.substring(4).trim());
            return value != null && !String.valueOf(value).isBlank();
        }
        log.debug("无法识别的路由条件，视为不命中: {}", expr);
        return false;
    }

    private int indexOfOperator(String expr, String op) {
        // 排除 "!=" 误匹配 "=="（按位置取最先出现的合法算子）
        int idx = expr.indexOf(op);
        if (idx > 0) {
            String prefix = expr.substring(0, idx).trim();
            if (prefix.startsWith("var:") && prefix.length() > 4) {
                return idx;
            }
        }
        return -1;
    }

    private boolean compare(String varName, String expected, OrchestrationContext ctx, boolean equals) {
        Object actual = ctx.getVariable(varName);
        String actualStr = actual != null ? String.valueOf(actual) : null;
        boolean result;
        if (actualStr != null && expected != null) {
            try {
                // 数字按数值比较（"1" == "1.0" 为真）
                result = Double.compare(Double.parseDouble(actualStr), Double.parseDouble(expected)) == 0;
            } catch (NumberFormatException e) {
                result = actualStr.equals(expected);
            }
        } else {
            result = actualStr == null && expected == null;
        }
        return equals ? result : !result;
    }

    private String extractVarName(String left) {
        String s = left.trim();
        return s.startsWith("var:") ? s.substring(4).trim() : s;
    }

    private String unquote(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
