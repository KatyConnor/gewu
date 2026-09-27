package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.runtime.WorkflowExpressionEvaluator;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 决策表节点测试（53 号 §3.3）：首行命中输出/无命中 default/非法表达式失败。 */
class DecisionHandlerTest {

    private DecisionHandler handler;
    private final AtomicReference<String> completed = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        handler = new DecisionHandler(new WorkflowExpressionEvaluator());
    }

    private void run(String config) throws Exception {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("e1");
        WorkflowNode node = new WorkflowNode();
        node.setId("d1");
        node.setNodeType("decision");
        node.setConfig(config);
        WorkflowNodeInstance row = new WorkflowNodeInstance();
        row.setId("r1");
        row.setInstanceId("e1");
        Map<String, Object> configMap = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(config, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        WorkflowNodeContext ctx = WorkflowNodeContext.of(instance, node, row,
                Map.of(), configMap, "", c -> completed.set((c.success() ? "OK:" : "ERR:") + c.outputJson()));
        handler.activate(ctx);
    }

    @Test
    @DisplayName("首个命中规则行输出其 value")
    void firstMatchingRuleWins() throws Exception {
        run("{\"decisions\": [" + ruleJson("1 == 2", "low") + "," + ruleJson("1 == 1", "high") + "]}");
        assertTrue(completed.get().startsWith("OK:") && completed.get().contains("high"),
                completed.get());
    }

    @Test
    @DisplayName("无命中输出 default")
    void fallsBackToDefault() throws Exception {
        run("{\"decisions\": [" + ruleJson("1 == 2", "low") + "], \"default\": \"none\"}");
        assertTrue(completed.get().contains("none"), completed.get());
    }

    @Test
    @DisplayName("非法 when 表达式走失败回调")
    void invalidWhenFails() throws Exception {
        run("{\"decisions\": [" + ruleJson("bad @ expr", "x") + "]}");
        assertTrue(completed.get().startsWith("ERR:"), completed.get());
    }

    private String ruleJson(String when, String value) {
        return "{\"when\": \"" + when + "\", \"value\": \"" + value + "\"}";
    }
}
