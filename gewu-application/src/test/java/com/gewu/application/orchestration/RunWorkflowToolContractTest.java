package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolResult;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.WorkflowInstanceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4-A 互操作契约冻结测试：agent→workflow 方向（RunWorkflowTool）。
 * <p>契约三要素（DEC/ADR-001 修订：双轨并存、互操作契约化）——
 * ①工具面（name=run_workflow/类别 WORKFLOW/参数 schema 锚定 workflowId 必填）
 * ②行为面（startByTrigger MANUAL 发起→轮询终态→final_output 透传；无服务降级失败）
 * ③超时面（等待上限到达即失败并如实告知后台继续，不静默伪装完成）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("互操作契约：RunWorkflowTool（agent→workflow，P4-A）")
class RunWorkflowToolContractTest {

    @Mock
    private WorkflowInstanceService instanceService;
    @Mock
    private ObjectProvider<WorkflowInstanceService> instanceServiceProvider;

    private RunWorkflowTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new RunWorkflowTool(instanceServiceProvider, new ObjectMapper());
        lenient().when(instanceServiceProvider.getIfAvailable()).thenReturn(instanceService);
        context = ToolContext.builder().userId("u1").agentId("a1").sessionId("s1").timeout(30).build();
    }

    private WorkflowInstanceDTO instance(String id, String status, String finalOutput) {
        return WorkflowInstanceDTO.builder()
                .instanceId(id).status(status).finalOutput(finalOutput).build();
    }

    @Test
    @Order(4)
    @DisplayName("契约①工具面：name/category/参数 schema 锚定（workflowId 必填）")
    void definitionExposesContractSurface() {
        ToolDefinition def = tool.getDefinition();

        assertEquals("run_workflow", def.getName());
        assertTrue(def.getDescription().contains("工作流"), "工具描述应声明工作流语义");
        assertTrue(def.getParameters().contains("workflowId"), "参数 schema 应含 workflowId");
        assertTrue(def.getParameters().contains("\"required\""), "参数 schema 应含 required 约束");
        assertEquals("WORKFLOW", RunWorkflowTool.class
                .getAnnotation(com.gewu.agent.engine.tool.ToolProvider.class).category());
    }

    @Test
    @Order(1)
    @DisplayName("契约②行为面：MANUAL 发起→轮询至终态→final_output 透传给 Agent 循环")
    void invokeStartsWorkflowAndReturnsFinalOutput() throws Exception {
        when(instanceService.startByTrigger(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(instance("wf-inst-1", "running", null));
        when(instanceService.getInstance(anyString())).thenAnswer(inv -> {
            System.err.println("[PROBE] getInstance(" + inv.getArgument(0) + ") -> completed");
            return instance("wf-inst-1", "completed", "审批通过，最终结果X");
        });

        ToolResult result = tool.invoke("{\"workflowId\":\"wf-1\",\"input\":\"{}\"}", context);

        assertTrue(result.isSuccess(), "成功路径应返回 success: " + result.getError());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("completed"), "状态透传");
        assertTrue(result.getOutput().contains("最终结果X"), "final_output 透传");
        assertTrue(result.getOutput().contains("wf-inst-1"), "实例 ID 透传");
        verify(instanceService).startByTrigger(eq("wf-1"), eq("u1"),
                anyString(), anyString(), eq("MANUAL"));
    }

    @Test
    @Order(2)
    @DisplayName("契约③超时面：等待上限到达即失败并如实告知后台继续（不静默伪装完成）")
    void timeoutFailsHonestly() throws Exception {
        when(instanceService.startByTrigger(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(instance("wf-inst-2", "running", null));
        when(instanceService.getInstance("wf-inst-2"))
                .thenReturn(instance("wf-inst-2", "running", null));
        tool.timeoutSeconds = 1; // 包级字段：契约测试缩等待窗口

        ToolResult result = tool.invoke("{\"workflowId\":\"wf-2\"}", context);

        assertFalse(result.isSuccess(), "超时应失败而非伪装完成");
        assertTrue(result.getError().contains("等待上限"), "失败原因含等待上限");
        assertTrue(result.getError().contains("后台继续执行"), "如实告知实例仍在后台");
    }

    @Test
    @Order(3)
    @DisplayName("降级面：工作流服务不可用/缺 workflowId → 明确失败（不挂起）")
    void degradedPathsFailFast() {
        when(instanceServiceProvider.getIfAvailable()).thenReturn(null);
        ToolResult noService = tool.invoke("{\"workflowId\":\"wf-3\"}", context);
        assertFalse(noService.isSuccess());
        assertTrue(noService.getError().contains("工作流服务不可用"));

        lenient().when(instanceServiceProvider.getIfAvailable()).thenReturn(instanceService);
        ToolResult noId = tool.invoke("{}", context);
        assertFalse(noId.isSuccess());
        assertTrue(noId.getError().contains("workflowId"));
    }
}
