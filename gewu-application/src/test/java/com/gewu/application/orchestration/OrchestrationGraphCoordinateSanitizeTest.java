package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphVersionMapper;
import com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationScheduleMapper;
import com.gewu.infrastructure.mapper.OrchestrationWebhookMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * 图定义坐标规范化测试（docs/design/55 P1-C）：
 * 建图入库前剔除非数字 x/y 字段（交设计器网格兜底），合法数字坐标（含 (0,0)）与缺失坐标原样保留。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationGraphCoordinateSanitizeTest {

    @Mock OrchestrationEngine orchestrationEngine;
    @Mock OrchestrationGraphMapper graphMapper;
    @Mock OrchestrationExecutionMapper executionMapper;
    @Mock ApprovalRequestMapper approvalMapper;
    @Mock com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    @Mock com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;
    @Mock OrchestrationNodeExecutionMapper nodeExecutionMapper;
    @Mock OrchestrationGraphVersionMapper versionMapper;
    @Mock OrchestrationScheduleMapper scheduleMapper;
    @Mock OrchestrationWebhookMapper webhookMapper;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(orchestrationEngine, graphMapper, executionMapper,
                approvalMapper, new ObjectMapper(), fourPhasePipeline, orchestrationTracer,
                new GraphDefinitionValidator(new ObjectMapper(), graphMapper), nodeExecutionMapper,
                versionMapper, scheduleMapper, webhookMapper);
    }

    private String createdDefinition(String definition) {
        service.createGraph("坐标规范化测试", definition, null, "PIPELINE", "u1");
        ArgumentCaptor<OrchestrationGraphEntity> captor = ArgumentCaptor.forClass(OrchestrationGraphEntity.class);
        verify(graphMapper).insert(captor.capture());
        return captor.getValue().getGraphDefinition();
    }

    @Test
    @DisplayName("非数字 x/y 入库前剔除，合法坐标（含 0,0）保留")
    void createGraphRemovesNonNumericCoordinates() {
        String stored = createdDefinition("{\"nodes\":["
                + "{\"nodeId\":\"n1\",\"type\":\"AGENT\",\"x\":26,\"y\":102},"
                + "{\"nodeId\":\"n2\",\"type\":\"ROUTER\",\"x\":\"0\",\"y\":null},"
                + "{\"nodeId\":\"n3\",\"type\":\"HUMAN\",\"x\":0,\"y\":0}"
                + "],\"edges\":[]}");
        assertTrue(stored.contains("\"nodeId\":\"n1\",\"type\":\"AGENT\",\"x\":26,\"y\":102"),
                "合法数字坐标应原样保留: " + stored);
        assertTrue(stored.contains("\"nodeId\":\"n2\",\"type\":\"ROUTER\"}"),
                "非数字 x 与 null y 应剔除且不引入新字段: " + stored);
        assertTrue(stored.contains("\"nodeId\":\"n3\",\"type\":\"HUMAN\",\"x\":0,\"y\":0"),
                "合法的 (0,0) 不是占位垃圾，应保留: " + stored);
        assertFalse(stored.contains("\"x\":\"0\""), "字符串坐标不得入库: " + stored);
        assertFalse(stored.contains("\"y\":null"), "null 坐标不得入库: " + stored);
    }

    @Test
    @DisplayName("缺失坐标的节点原样透传，不注入坐标")
    void createGraphKeepsMissingCoordinatesUntouched() {
        String stored = createdDefinition("{\"nodes\":["
                + "{\"nodeId\":\"n1\"},"
                + "{\"nodeId\":\"n2\",\"x\":0,\"y\":0}"
                + "],\"edges\":[]}");
        assertTrue(stored.contains("{\"nodeId\":\"n1\"}"),
                "无坐标节点不应被注入坐标字段: " + stored);
        assertTrue(stored.contains("\"nodeId\":\"n2\",\"x\":0,\"y\":0"),
                "合法坐标节点应保持不变: " + stored);
    }
}
