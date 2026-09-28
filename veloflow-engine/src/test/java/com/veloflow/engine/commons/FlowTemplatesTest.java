package com.veloflow.engine.commons;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 变量模板渲染测试（51 号 §2.5 P4）：${} 替换 / XSS 实体还原 / 缺失变量 */
class FlowTemplatesTest {

    @Test
    @DisplayName("基本替换：${var} 渲染为变量值")
    void basicSubstitution() {
        Map<String, Object> vars = Map.of("order", "A001", "amount", 99);
        assertEquals("订单 A001 金额 99 元",
                FlowTemplates.render("订单 ${order} 金额 ${amount} 元", vars));
    }

    @Test
    @DisplayName("XSS 实体还原：CR-019 中和的 &gt;/&lt; 先还原再渲染")
    void unescapesHtmlEntities() {
        assertEquals("a > b 与 c < d",
                FlowTemplates.render("a &gt; b 与 c &lt; d", Map.of()));
    }

    @Test
    @DisplayName("缺失变量渲染为空串；非模板文本原样返回")
    void missingVariableRendersEmpty() {
        assertEquals("x=，ok", FlowTemplates.render("x=${missing}，ok", Map.of()));
        assertEquals("plain text", FlowTemplates.render("plain text", Map.of()));
    }

    @Test
    @DisplayName("null 模板返回空串；值含 ${} 嵌套不递归展开")
    void nullTemplateAndNoRecursion() {
        assertEquals("", FlowTemplates.render(null, Map.of()));
        Map<String, Object> vars = new HashMap<>();
        vars.put("v", "${v}");
        assertEquals("${v}", FlowTemplates.render("${v}", vars));
    }
}
