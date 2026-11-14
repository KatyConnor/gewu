package com.veloflow.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4-A 互操作契约冻结（宿主侧）：FlowAiBridge SPI 面反射锁定——
 * 双轨互操作契约化（DEC/ADR-001 修订：双轨并存），宿主 VeloflowAiBridge 与
 * 节点 Handler（Llm/Agent/Orchestration/Knowledge）均编译依赖此 SPI 面，
 * 方法签名漂移即测试失败（防止静默破坏跨轨契约）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@DisplayName("互操作契约：FlowAiBridge SPI 面（workflow→agent，P4-A）")
class FlowAiBridgeContractTest {

    @Test
    @DisplayName("契约面锁定：四桥接方法签名（invokeLlm/invokeAgent/invokeOrchestration/searchKnowledge）")
    void bridgeSurfaceLocked() {
        List<String> required = List.of(
                "invokeLlm", "invokeAgent", "invokeOrchestration", "searchKnowledge");
        List<String> actual = Arrays.stream(FlowAiBridge.class.getDeclaredMethods())
                .map(Method::getName)
                .distinct()
                .toList();
        List<String> missing = required.stream().filter(r -> !actual.contains(r)).toList();
        assertTrue(missing.isEmpty(), "FlowAiBridge 契约面漂移，缺少: " + missing);
        assertEquals(required.size(), actual.size(),
                "FlowAiBridge 出现契约外新增方法（跨轨契约变更须走契约评审）: " + actual);
    }

    @Test
    @DisplayName("嵌套契约记录锁定：LlmSpec/LlmResult/AgentSpec/AgentResult 字段面")
    void nestedRecordsLocked() throws Exception {
        Class<?> llmSpec = Class.forName("com.veloflow.engine.ai.FlowAiBridge$LlmSpec");
        assertEquals(List.of("modelProvider", "modelName", "prompt", "temperature", "maxTokens"),
                recordComponentNames(llmSpec));
        Class<?> llmResult = Class.forName("com.veloflow.engine.ai.FlowAiBridge$LlmResult");
        assertEquals(List.of("content", "totalTokens", "model"), recordComponentNames(llmResult));
        Class<?> agentSpec = Class.forName("com.veloflow.engine.ai.FlowAiBridge$AgentSpec");
        assertEquals(List.of("agentId", "task", "workflowInstanceId", "workflowName"),
                recordComponentNames(agentSpec));
    }

    private List<String> recordComponentNames(Class<?> type) {
        assertTrue(type.isRecord(), type.getSimpleName() + " 应为 record（契约面形态锁定）");
        return Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).toList();
    }
}
