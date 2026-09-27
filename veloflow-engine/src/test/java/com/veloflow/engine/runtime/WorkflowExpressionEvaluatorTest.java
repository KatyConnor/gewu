package com.veloflow.engine.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流表达式求值器测试（51 号 §五）：语法、比较、逻辑、路径、白名单函数、非法输入。
 */
class WorkflowExpressionEvaluatorTest {

    private final WorkflowExpressionEvaluator evaluator = new WorkflowExpressionEvaluator();

    @Test
    @DisplayName("变量路径与嵌套取值")
    void resolvesVariablePaths() {
        Map<String, Object> vars = Map.of(
                "amount", 150,
                "order", Map.of("status", "PAID", "customer", Map.of("level", "VIP")),
                "items", List.of("a", "b"));
        assertEquals(150, ((Number) evaluator.evaluate("amount", vars)).intValue());
        assertEquals("PAID", evaluator.evaluate("order.status", vars));
        assertEquals("VIP", evaluator.evaluate("order.customer.level", vars));
        assertEquals("b", evaluator.evaluate("items.1", vars));
    }

    @Test
    @DisplayName("比较与逻辑组合")
    void comparesAndCombines() {
        Map<String, Object> vars = Map.of("score", 85, "level", "VIP");
        assertTrue(evaluator.evaluateBoolean("score > 60 && level == 'VIP'", vars));
        assertFalse(evaluator.evaluateBoolean("score < 60 || level != 'VIP'", vars));
        assertTrue(evaluator.evaluateBoolean("not isEmpty(level)", vars));
        assertTrue(evaluator.evaluateBoolean("(score >= 85) or (level == 'NORMAL')", vars));
    }

    @Test
    @DisplayName("白名单函数")
    void evaluatesWhitelistedFunctions() {
        Map<String, Object> vars = Map.of("name", "", "text", "hello world", "list", List.of(1, 2, 3));
        assertTrue(evaluator.evaluateBoolean("isEmpty(name)", vars));
        assertTrue(evaluator.evaluateBoolean("contains(text, 'world')", vars));
        assertEquals(3, ((Number) evaluator.evaluate("size(list)", vars)).intValue());
        assertTrue((long) evaluator.evaluate("now()", vars) > 0);
    }

    @Test
    @DisplayName("未定义变量返回 null（真值判定为假）")
    void undefinedVariableIsNull() {
        assertFalse(evaluator.evaluateBoolean("missingVar", Map.of()));
        assertTrue(evaluator.evaluateBoolean("isEmpty(missingVar)", Map.of()));
    }

    @Test
    @DisplayName("语法错误抛可读异常（WV-07 语法闸）")
    void syntaxErrorsThrow() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("a == ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("(a && b", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("a @ b", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("'unclosed", Map.of()));
    }

    @Test
    @DisplayName("非白名单函数与方法调用被拒绝")
    void rejectsUnknownFunctions() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("exec('x')", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("vars.toString()", Map.of()));
    }

    @Test
    @DisplayName("表达式超长被拒绝（注入面收敛）")
    void rejectsOverlongExpression() {
        String longExpr = "a == 'x' && ".repeat(120) + "a == 'y'";
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(longExpr, Map.of()));
    }

    @Test
    @DisplayName("validateSyntax 仅语法校验（变量不存在不报错）")
    void validateSyntaxToleratesUnknownVars() {
        evaluator.validateSyntax("unknown.path.field == 'value'");
    }
}
