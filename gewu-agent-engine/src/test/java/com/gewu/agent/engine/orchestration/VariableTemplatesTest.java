package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 变量模板渲染测试（WFO-06）：
 * 标准占位符 ${name} 渲染、历史前缀写法 ${var.name} 兼容降级、未定义变量空串。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-09-26
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

    // ==================== AE-P1-6（49 号 F-10）：结构化 JSON 参数渲染 ====================

    @Test
    @DisplayName("JSON 参数：上游变量含引号/换行经序列化转义，不再碎裂外层结构")
    void jsonArgumentsEscapeUpstreamQuotes() {
        OrchestrationContext context = ctx("note", "他说\"删除\"并\n换行");
        String rendered = VariableTemplates.renderJsonArguments(
                "{\"sql\":\"${note}\",\"limit\":10}", context);
        // 渲染结果必须仍是合法 JSON 且值完整保留（纯文本渲染会在引号处碎裂）
        com.fasterxml.jackson.databind.JsonNode root =
                parse(rendered);
        org.junit.jupiter.api.Assertions.assertEquals("他说\"删除\"并\n换行", root.path("sql").asText());
        org.junit.jupiter.api.Assertions.assertEquals(10, root.path("limit").asInt());
    }

    @Test
    @DisplayName("JSON 参数：嵌套对象/数组内的字符串值同样渲染")
    void jsonArgumentsRenderNestedValues() {
        OrchestrationContext context = ctx("city", "杭州", "kw", "查询A");
        String rendered = VariableTemplates.renderJsonArguments(
                "{\"query\":{\"city\":\"${city}\"},\"tags\":[\"${kw}\"]}", context);
        com.fasterxml.jackson.databind.JsonNode root = parse(rendered);
        org.junit.jupiter.api.Assertions.assertEquals("杭州", root.path("query").path("city").asText());
        org.junit.jupiter.api.Assertions.assertEquals("查询A", root.path("tags").get(0).asText());
    }

    @Test
    @DisplayName("非 JSON 模板回退纯文本渲染（旧行为兼容）")
    void nonJsonTemplateFallsBackToTextRender() {
        OrchestrationContext context = ctx("input", "你好");
        // 有 ${} 但非合法 JSON：回退 render()
        assertEquals("前 你好 后", VariableTemplates.renderJsonArguments("前 ${input} 后", context));
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("渲染结果不是合法 JSON: " + json, e);
        }
    }
}
