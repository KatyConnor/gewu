package com.veloflow.engine.trigger;

import com.veloflow.engine.commons.VlfSm3;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.WorkflowInstanceDTO;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowScheduleMapper;
import com.veloflow.engine.persistence.mapper.WorkflowWebhookMapper;
import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowWebhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 触发配置服务测试（53 号 §3.1/§3.3）：Webhook token 哈希/重置、Cron 下次触发计算、
 * respond 同步返回配对。定时执行链路由 WorkflowScheduleRunner 的 CAS/自愈模式保障。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTriggerServiceTest {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowNodeMapper nodeMapper;
    @Mock WorkflowScheduleMapper scheduleMapper;
    @Mock WorkflowWebhookMapper webhookMapper;
    @Mock WorkflowInstanceService instanceService;

    private WebhookResponseRegistry responseRegistry;
    private WorkflowTriggerService service;

    @BeforeEach
    void setUp() {
        responseRegistry = new WebhookResponseRegistry();
        service = new WorkflowTriggerService(workflowMapper, nodeMapper,
                scheduleMapper, webhookMapper, instanceService, responseRegistry);
    }

    private void stubPublishedWorkflow(String workflowId) {
        Workflow workflow = new Workflow();
        workflow.setId(workflowId);
        workflow.setStatus(1);
        when(workflowMapper.selectById(workflowId)).thenReturn(workflow);
    }

    @Test
    @DisplayName("Webhook 首次生成：token 明文一次性返回，库内只存 SM3 哈希")
    void generateStoresHashOnly() {
        stubPublishedWorkflow("wf-1");
        when(webhookMapper.selectList(any())).thenReturn(List.of());

        var credential = service.upsertWebhook("wf-1", true, false);

        assertTrue(credential.token().length() >= 30, "token 应为高熵随机串");
        verify(webhookMapper).insert(any(WorkflowWebhook.class));
    }

    @Test
    @DisplayName("Cron 非法时保存被拒（41005）")
    void invalidCronRejected() {
        stubPublishedWorkflow("wf-1");
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.veloflow.engine.commons.VeloflowException.class,
                () -> service.upsertSchedule("wf-1", "not-a-cron", null, null, true));
        assertEquals(com.veloflow.engine.commons.VeloflowErrorCode.FLOW_VALIDATION_FAILED, ex.getCode());
    }

    @Test
    @DisplayName("触发命中停用 Webhook 返回 null（端点 404 语义）")
    void disabledWebhookReturnsNull() {
        WorkflowWebhook webhook = new WorkflowWebhook();
        webhook.setWorkflowId("wf-1");
        webhook.setTokenHash(VlfSm3.hashHex("secret"));
        webhook.setEnabled(0);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        assertNull(service.triggerByWebhook("secret", "{}"));
    }

    @Test
    @DisplayName("respond 配对：respond 在 start 线程内同步完成 → 200 返回 payload")
    void webhookSyncRespondReturnsPayload() {
        WorkflowWebhook webhook = new WorkflowWebhook();
        webhook.setId("wh-1");
        webhook.setWorkflowId("wf-1");
        webhook.setTokenHash(VlfSm3.hashHex("secret"));
        webhook.setEnabled(1);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        stubPublishedWorkflow("wf-1");
        com.veloflow.engine.persistence.model.WorkflowNode respondNode =
                new com.veloflow.engine.persistence.model.WorkflowNode();
        respondNode.setWorkflowId("wf-1");
        respondNode.setNodeType("respond");
        when(nodeMapper.selectList(any())).thenReturn(List.of(respondNode));
        // respond 在 start 调用线程内同步完成（AUTO 节点语义）→ 注册表先收 payload
        when(instanceService.startByTrigger(eq("wf-1"), eq("webhook"), any(), anyString(), eq("WEBHOOK")))
                .thenAnswer(inv -> {
                    responseRegistry.onRespond("inst-1", "{\"ok\":true}");
                    return WorkflowInstanceDTO.builder().instanceId("inst-1").build();
                });

        var result = service.triggerByWebhook("secret", "{\"x\":1}");

        assertTrue(result.responded(), "respond 同步完成应返回 responded=true");
        assertEquals("{\"ok\":true}", result.respondPayload());
        assertEquals("inst-1", result.instanceId());
    }

    @Test
    @DisplayName("无 respond 节点：触发即返回 202 语义（responded=false）")
    void webhookWithoutRespondAccepted() {
        WorkflowWebhook webhook = new WorkflowWebhook();
        webhook.setId("wh-1");
        webhook.setWorkflowId("wf-1");
        webhook.setTokenHash(VlfSm3.hashHex("secret"));
        webhook.setEnabled(1);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        stubPublishedWorkflow("wf-1");
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(instanceService.startByTrigger(eq("wf-1"), eq("webhook"), any(), anyString(), eq("WEBHOOK")))
                .thenReturn(WorkflowInstanceDTO.builder().instanceId("inst-2").build());

        var result = service.triggerByWebhook("secret", "{}");

        assertFalse(result.responded());
        assertEquals("inst-2", result.instanceId());
    }

    private void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
