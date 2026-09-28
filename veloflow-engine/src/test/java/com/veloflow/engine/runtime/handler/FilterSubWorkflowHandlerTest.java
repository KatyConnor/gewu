package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.WorkflowInstanceDTO;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门控过滤与子工作流节点测试（53 号 §3.3/§3.7）：pass/block 路由输出、
 * 防自环、深度上限、同步终态直推、异步挂起登记 childInstanceId。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FilterSubWorkflowHandlerTest {

    @Mock WorkflowInstanceService instanceService;
    @Mock WorkflowNodeContext context;
    @Mock WorkflowInstance instance;
    private WorkflowNodeInstance nodeInstance;

    private FilterHandler filterHandler;
    private SubWorkflowHandler subWorkflowHandler;

    @BeforeEach
    void setUp() {
        filterHandler = new FilterHandler(new com.veloflow.engine.runtime.WorkflowExpressionEvaluator());
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowInstanceService> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(instanceService);
        subWorkflowHandler = new SubWorkflowHandler(provider);
        lenient().when(context.instance()).thenReturn(instance);
        lenient().when(instance.getId()).thenReturn("inst-parent");
        lenient().when(instance.getWorkflowId()).thenReturn("wf-parent");
        lenient().when(instance.getInitiatorId()).thenReturn("user-1");
        lenient().when(instance.getTitle()).thenReturn("父流程");
        nodeInstance = new WorkflowNodeInstance();
        lenient().when(context.nodeInstance()).thenReturn(nodeInstance);
    }

    @Test
    @DisplayName("filter：表达式为真输出 matched=pass")
    void filterPass() {
        when(context.config()).thenReturn(Map.of("expression", "amount > 100"));
        when(context.variables()).thenReturn(Map.of("amount", 200));

        filterHandler.activate(context);

        verify(context).complete("{\"matched\": \"pass\"}");
    }

    @Test
    @DisplayName("filter：表达式为假输出 matched=block")
    void filterBlock() {
        when(context.config()).thenReturn(Map.of("expression", "amount > 100"));
        when(context.variables()).thenReturn(Map.of("amount", 50));

        filterHandler.activate(context);

        verify(context).complete("{\"matched\": \"block\"}");
    }

    @Test
    @DisplayName("sub-workflow：拒绝自引用工作流")
    void rejectsSelfReference() {
        when(context.config()).thenReturn(Map.of("workflowId", "wf-parent"));

        subWorkflowHandler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("自环"));
    }

    @Test
    @DisplayName("sub-workflow：嵌套深度超限拒绝（防递归实例膨胀）")
    void rejectsDepthOverflow() {
        when(context.config()).thenReturn(Map.of("workflowId", "wf-child"));
        when(context.variables()).thenReturn(Map.of(SubWorkflowHandler.DEPTH_VAR, SubWorkflowHandler.MAX_DEPTH));

        subWorkflowHandler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("深度超限"));
    }

    @Test
    @DisplayName("sub-workflow：子流程同步完成 → 父节点直接以子 finalOutput 完成")
    void childSyncCompletionDrivesParent() {
        when(context.config()).thenReturn(Map.of("workflowId", "wf-child"));
        when(context.variables()).thenReturn(Map.of());
        when(instanceService.startByTrigger(eq("wf-child"), eq("user-1"), anyString(), anyString(), eq("MANUAL")))
                .thenReturn(WorkflowInstanceDTO.builder()
                        .instanceId("inst-child").status("running").build());
        // 状态按 DB 重取（启动线程内存快照不可信）
        when(instanceService.getInstance("inst-child")).thenReturn(WorkflowInstanceDTO.builder()
                .instanceId("inst-child").status("completed").finalOutput("{\"r\":1}").build());

        subWorkflowHandler.activate(context);

        verify(context).complete("{\"r\":1}");
    }

    @Test
    @DisplayName("sub-workflow：子流程异步挂起 → 父行登记 childInstanceId 保持 waiting")
    void asyncChildRegistersChildInstanceId() {
        when(context.config()).thenReturn(Map.of("workflowId", "wf-child"));
        when(context.variables()).thenReturn(Map.of());
        when(instanceService.startByTrigger(eq("wf-child"), eq("user-1"), anyString(), anyString(), eq("MANUAL")))
                .thenReturn(WorkflowInstanceDTO.builder()
                        .instanceId("inst-child").status("running").build());
        when(instanceService.getInstance("inst-child")).thenReturn(WorkflowInstanceDTO.builder()
                .instanceId("inst-child").status("running").build());

        subWorkflowHandler.activate(context);

        assertNotNull(nodeInstance.getChildInstanceId());
        assertEquals("inst-child", nodeInstance.getChildInstanceId());
        verify(context, org.mockito.Mockito.never()).complete(any(String.class));
    }
}
