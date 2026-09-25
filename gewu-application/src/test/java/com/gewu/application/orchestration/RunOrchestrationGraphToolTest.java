package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Agent 工具化调用编排图测试（WFC-01）：
 * 参数校验、非 active 图拒绝、成功回传执行状态与最终输出。
 */
@ExtendWith(MockitoExtension.class)
class RunOrchestrationGraphToolTest {

    @Mock OrchestrationService orchestrationService;

    private RunOrchestrationGraphTool tool;

    @BeforeEach
    void setUp() {
        tool = new RunOrchestrationGraphTool(orchestrationService, new ObjectMapper());
    }

    @Test
    @DisplayName("工具定义：名称与参数 Schema 可解析且 graphId 必填")
    void definitionExposesSchema() throws Exception {
        var definition = tool.getDefinition();
        assertEquals("run_orchestration_graph", definition.getName());
        var schema = new ObjectMapper().readTree(definition.getParameters());
        assertTrue(schema.get("required").toString().contains("graphId"));
    }

    @Test
    @DisplayName("缺少 graphId 参数返回失败")
    void missingGraphIdFails() {
        ToolResult result = tool.invoke("{\"input\":\"x\"}", context());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("graphId"));
    }

    @Test
    @DisplayName("非 JSON 参数返回失败")
    void malformedArgumentsFail() {
        ToolResult result = tool.invoke("not-json", context());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("参数解析失败"));
    }

    @Test
    @DisplayName("图不存在或未激活（Service 抛错）返回可读失败")
    void inactiveGraphFails() {
        when(orchestrationService.executeGraphForAgentTool(eq("g-1"), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("编排图不存在或未激活，无法通过 Agent 工具运行: g-1"));
        ToolResult result = tool.invoke("{\"graphId\":\"g-1\"}", context());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("未激活"));
    }

    @Test
    @DisplayName("执行成功回传状态与最终输出")
    void successReturnsOutput() {
        OrchestrationExecutionEntity execution = new OrchestrationExecutionEntity();
        execution.setId("e-1");
        execution.setStatus("SUCCEEDED");
        execution.setFinalOutput("流程产出内容");
        when(orchestrationService.executeGraphForAgentTool(eq("g-2"), any(), any(), eq("任务输入")))
                .thenReturn(execution);
        ToolResult result = tool.invoke("{\"graphId\":\"g-2\",\"input\":\"任务输入\"}", context());
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("SUCCEEDED"));
        assertTrue(result.getOutput().contains("流程产出内容"));
    }

    private ToolContext context() {
        return ToolContext.builder().userId("u1").sessionId("s1").build();
    }
}
