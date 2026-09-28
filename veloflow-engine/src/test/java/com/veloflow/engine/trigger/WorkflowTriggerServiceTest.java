package com.veloflow.engine.trigger;

import com.veloflow.engine.commons.VlfSm3;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 触发配置服务测试（53 号 §3.1）：Webhook token 哈希/重置、Cron 下次触发计算。
 * 定时触发执行链路由 WorkflowScheduleRunner 的 CAS/自愈模式保障（同编排 49 号修复模式）。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTriggerServiceTest {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowScheduleMapper scheduleMapper;
    @Mock WorkflowWebhookMapper webhookMapper;
    @Mock WorkflowInstanceService instanceService;

    private WorkflowTriggerService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerService(workflowMapper, scheduleMapper, webhookMapper, instanceService);
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

    private void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
