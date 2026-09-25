package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 变量模板渲染测试（WFO-06）：
 * 标准占位符 ${name} 渲染、历史前缀写法 ${var.name} 兼容降级、未定义变量空串。
 */
class VariableTemplatesTest {

    private OrchestrationContext ctx(String... kv) {
        OrchestrationContext context = OrchestrationContext.builder().executionId("e1").build();
        for (int i = 0; i < kv.length; i += 2) {
            context.putVariable(kv[i], kv[i + 1]);
        }
        return context;
    }

    @Test
    @DisplayName("标准占位符 ${name} 渲染为上下文变量值")
    void rendersStandardPlaceholder() {
        OrchestrationContext context = ctx("input", "你好", "n1", "产出A");
        assertEquals("任务: 你好 / 上游: 产出A",
                VariableTemplates.render("任务: ${input} / 上游: ${n1}", context));
    }

    @Test
    @DisplayName("历史前缀写法 ${var.name} 未命中字面变量时去前缀降级解析")
    void degradesVarPrefixToPlainName() {
        OrchestrationContext context = ctx("input", "你好");
        // 上下文无名为 "var.input" 的变量：应降级按 ${input} 解析，而非渲染为空串
        assertEquals("输入是 你好", VariableTemplates.render("输入是 ${var.input}", context));
    }

    @Test
    @DisplayName("确实存在 var.name 字面变量时优先字面匹配（不降级）")
    void prefersLiteralVariableName() {
        OrchestrationContext context = ctx("var.input", "字面值");
        assertEquals("输入是 字面值", VariableTemplates.render("输入是 ${var.input}", context));
    }

    @Test
    @DisplayName("未定义变量替换为空串")
    void undefinedVariableRendersEmpty() {
        OrchestrationContext context = ctx("input", "你好");
        assertEquals("前[]后", VariableTemplates.render("前[${missing}]后", context));
    }

    @Test
    @DisplayName("不含占位符的模板原样返回")
    void templateWithoutPlaceholderUntouched() {
        assertEquals("plain text", VariableTemplates.render("plain text", ctx()));
    }
}
