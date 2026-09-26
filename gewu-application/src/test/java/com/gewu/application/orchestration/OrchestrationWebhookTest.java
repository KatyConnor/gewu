package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.common.crypto.SM3Util;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.domain.orchestration.OrchestrationWebhookEntity;
import com.gewu.infrastructure.mapper.OrchestrationWebhookMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 触发测试（WFC-03）：
 * token 生成/哈希落库、错误与停用 token 的 404 语义（null）、命中后 triggerType=WEBHOOK 执行。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationWebhookTest {

    private static final String DEFINITION =
            "{\"mode\":\"PIPELINE\",\"nodes\":[{\"nodeId\":\"n1\",\"type\":\"AGENT\"}],\"edges\":[]}";

    @Mock OrchestrationEngine orchestrationEngine;
    @Mock com.gewu.infrastructure.mapper.OrchestrationGraphMapper graphMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationExecutionMapper executionMapper;
    @Mock com.gewu.infrastructure.mapper.ApprovalRequestMapper approvalMapper;
    @Mock com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    @Mock com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;
    @Mock com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper nodeExecutionMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationGraphVersionMapper versionMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationScheduleMapper scheduleMapper;
    @Mock OrchestrationWebhookMapper webhookMapper;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(orchestrationEngine, graphMapper, executionMapper,
                approvalMapper, new ObjectMapper(), fourPhasePipeline, orchestrationTracer,
                new GraphDefinitionValidator(new ObjectMapper(), graphMapper), nodeExecutionMapper,
                versionMapper, scheduleMapper, webhookMapper);
    }

    private OrchestrationGraphEntity activeGraph() {
        OrchestrationGraphEntity entity = new OrchestrationGraphEntity();
        entity.setId("g-1");
        entity.setStatus("active");
        entity.setOrchestrationMode("PIPELINE");
        entity.setGraphDefinition(DEFINITION);
        return entity;
    }

    @Test
    @DisplayName("首次生成：明文 token 返回一次，库内只存 SM3 哈希")
    void generateStoresHashOnly() {
        when(graphMapper.selectById("g-1")).thenReturn(activeGraph());
        when(webhookMapper.selectList(any())).thenReturn(List.of());

        OrchestrationService.WebhookCredential credential = service.upsertWebhook("g-1", true, false, "u1");

        assertNotNull(credential.token());
        assertTrue(credential.token().length() >= 30, "token 应为高熵随机串");
        ArgumentCaptor<OrchestrationWebhookEntity> captor = ArgumentCaptor.forClass(OrchestrationWebhookEntity.class);
        verify(webhookMapper).insert(captor.capture());
        assertEquals(SM3Util.hashHex(credential.token()), captor.getValue().getTokenHash());
        assertEquals(1, captor.getValue().getEnabled());
    }

    @Test
    @DisplayName("错误 token 触发返回 null（端点统一 404）")
    void unknownTokenReturnsNull() {
        when(webhookMapper.selectList(any())).thenReturn(List.of());
        assertNull(service.triggerByWebhook("wrong-token", "{}"));
    }

    @Test
    @DisplayName("停用状态的 token 触发返回 null")
    void disabledWebhookReturnsNull() {
        OrchestrationWebhookEntity webhook = new OrchestrationWebhookEntity();
        webhook.setId("w-1");
        webhook.setGraphId("g-1");
        webhook.setTokenHash(SM3Util.hashHex("secret"));
        webhook.setEnabled(0);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        assertNull(service.triggerByWebhook("secret", "{}"));
    }

    @Test
    @DisplayName("命中启用中的 token 且图为 active：以 WEBHOOK 触发类型执行")
    void validTokenTriggersExecution() {
        OrchestrationWebhookEntity webhook = new OrchestrationWebhookEntity();
        webhook.setId("w-1");
        webhook.setGraphId("g-1");
        webhook.setTokenHash(SM3Util.hashHex("secret"));
        webhook.setEnabled(1);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        when(graphMapper.selectById("g-1")).thenReturn(activeGraph());
        when(fourPhasePipeline.executeGraph(any(), any()))
                .thenReturn(OrchestrationResult.success("e-1", "ok"));

        var execution = service.triggerByWebhook("secret", "{\"payload\":1}");

        assertNotNull(execution);
        assertEquals("WEBHOOK", execution.getTriggerType());
        assertEquals("webhook", execution.getUserId());
    }

    @Test
    @DisplayName("token 命中但图为 draft：返回 null（不可经 Webhook 执行）")
    void draftGraphWebhookReturnsNull() {
        OrchestrationWebhookEntity webhook = new OrchestrationWebhookEntity();
        webhook.setId("w-2");
        webhook.setGraphId("g-2");
        webhook.setTokenHash(SM3Util.hashHex("secret2"));
        webhook.setEnabled(1);
        when(webhookMapper.selectList(any())).thenReturn(List.of(webhook));
        OrchestrationGraphEntity draft = activeGraph();
        draft.setStatus("draft");
        when(graphMapper.selectById("g-2")).thenReturn(draft);

        assertNull(service.triggerByWebhook("secret2", "{}"));
    }

    @Test
    @DisplayName("重置 token：旧哈希被替换")
    void regenerateReplacesHash() {
        when(graphMapper.selectById("g-1")).thenReturn(activeGraph());
        OrchestrationWebhookEntity existing = new OrchestrationWebhookEntity();
        existing.setId("w-1");
        existing.setGraphId("g-1");
        existing.setTokenHash(SM3Util.hashHex("old"));
        existing.setEnabled(1);
        when(webhookMapper.selectList(any())).thenReturn(List.of(existing));

        OrchestrationService.WebhookCredential credential = service.upsertWebhook("g-1", true, true, "u1");

        assertEquals(SM3Util.hashHex(credential.token()), existing.getTokenHash());
        verify(webhookMapper).updateById(existing);
    }
}
