package com.veloflow.engine.trigger;

import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.mapper.WorkflowEventSubscriptionMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowEventSubscription;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件桥与上游触发链测试（53 号 §3.1/§3.3）：event-wait 唤醒、event-trigger /
 * upstream-trigger 自动发起、实例失败订阅清理。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEventServiceTest {

    @Mock WorkflowEventSubscriptionMapper subscriptionMapper;
    @Mock WorkflowNodeMapper nodeMapper;
    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowInstanceService instanceService;

    private WorkflowEventService service;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测环境初始化 MyBatis-Plus 实体元数据（Lambda 条件构造依赖）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.veloflow.engine.persistence.model.WorkflowEventSubscription.class);
    }

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<WorkflowInstanceService> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.lenient().when(provider.getObject()).thenReturn(instanceService);
        service = new WorkflowEventService(subscriptionMapper, nodeMapper, workflowMapper, provider);
    }

    private WorkflowEventSubscription waitingSub(String eventType) {
        WorkflowEventSubscription sub = new WorkflowEventSubscription();
        sub.setId("sub-1");
        sub.setSubscriptionType("WAIT");
        sub.setWorkflowId("wf-1");
        sub.setInstanceId("inst-1");
        sub.setNodeId("node-1");
        sub.setNodeInstanceId("ni-1");
        sub.setEventType(eventType);
        sub.setStatus("waiting");
        return sub;
    }

    @Test
    @DisplayName("事件交付唤醒匹配 event-wait：系统完成节点推进")
    void onEventWakesWaitSubscription() {
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(waitingSub("order.paid")));

        var result = service.onEvent("order.paid", "{\"amount\":100}");

        assertEquals(1, result.wokenWaitNodes());
        verify(instanceService).completeNodeBySystem(eq("inst-1"), eq("ni-1"),
                eq("{\"amount\":100}"), anyString());
    }

    @Test
    @DisplayName("事件交付自动发起匹配 event-trigger 的已发布流程")
    void onEventStartsEventTriggerWorkflow() {
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        WorkflowNode triggerNode = new WorkflowNode();
        triggerNode.setWorkflowId("wf-9");
        triggerNode.setNodeType("event-trigger");
        triggerNode.setConfig("{\"eventType\":\"order.paid\"}");
        when(nodeMapper.selectList(any())).thenReturn(List.of(triggerNode));
        Workflow published = new Workflow();
        published.setId("wf-9");
        published.setStatus(1);
        when(workflowMapper.selectById("wf-9")).thenReturn(published);

        var result = service.onEvent("order.paid", "{}");

        assertEquals(1, result.startedInstances());
        verify(instanceService).startByTrigger(eq("wf-9"), eq("event"), any(), anyString(), eq("EVENT"));
    }

    @Test
    @DisplayName("eventType 不匹配的 event-trigger 流程不发起")
    void onEventSkipsUnmatchedTrigger() {
        when(subscriptionMapper.selectList(any())).thenReturn(List.of());
        WorkflowNode triggerNode = new WorkflowNode();
        triggerNode.setWorkflowId("wf-9");
        triggerNode.setNodeType("event-trigger");
        triggerNode.setConfig("{\"eventType\":\"other.event\"}");
        when(nodeMapper.selectList(any())).thenReturn(List.of(triggerNode));

        var result = service.onEvent("order.paid", "{}");

        assertEquals(0, result.startedInstances());
        verify(instanceService, never()).startByTrigger(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("上游实例完成：匹配 upstreamWorkflowId 的下游流程自动发起（input=上游终态输出）")
    void upstreamCompletionStartsDownstream() {
        WorkflowInstance upstream = new WorkflowInstance();
        upstream.setId("inst-up");
        upstream.setWorkflowId("wf-up");
        upstream.setStatus("completed");
        upstream.setFinalOutput("{\"total\":5}");
        WorkflowNode triggerNode = new WorkflowNode();
        triggerNode.setWorkflowId("wf-down");
        triggerNode.setNodeType("upstream-trigger");
        triggerNode.setConfig("{\"upstreamWorkflowId\":\"wf-up\"}");
        when(nodeMapper.selectList(any())).thenReturn(List.of(triggerNode));
        Workflow published = new Workflow();
        published.setId("wf-down");
        published.setStatus(1);
        when(workflowMapper.selectById("wf-down")).thenReturn(published);

        service.onInstanceCompleted(upstream);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceService).startByTrigger(eq("wf-down"), eq("upstream"), any(),
                inputCaptor.capture(), eq("UPSTREAM"));
        assertEquals("{\"total\":5}", inputCaptor.getValue());
    }

    @Test
    @DisplayName("实例失败：waiting 订阅全部取消")
    void instanceCancelledCancelsSubscriptions() {
        WorkflowInstance failed = new WorkflowInstance();
        failed.setId("inst-1");
        failed.setStatus("failed");

        service.onInstanceCancelled(failed);

        verify(subscriptionMapper).update(any(), any());
    }
}
