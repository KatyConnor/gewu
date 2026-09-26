package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：执行上下文必须合并图定义 variables（回归背景——
 * 图变量 modelProvider/modelName 曾从未进入上下文，导致节点级模型兜底失效，
 * 冒烟中 AGENT 节点报"无法解析 LLM 供应商与模型"）。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationContextVariablesTest {

    @Mock OrchestrationEngine orchestrationEngine;
    @Mock OrchestrationGraphMapper graphMapper;
    @Mock OrchestrationExecutionMapper executionMapper;
    @Mock ApprovalRequestMapper approvalMapper;
    @Mock com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    @Mock com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;
    @Mock com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper nodeExecutionMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationGraphVersionMapper versionMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationScheduleMapper scheduleMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationWebhookMapper webhookMapper;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(orchestrationEngine, graphMapper, executionMapper,
                approvalMapper, new ObjectMapper(), fourPhasePipeline, orchestrationTracer,
                new GraphDefinitionValidator(new ObjectMapper(), graphMapper), nodeExecutionMapper, versionMapper, scheduleMapper, webhookMapper);
    }

    @Test
    @DisplayName("执行上下文合并图定义 variables，input 覆盖同名模板键")
    void contextVariablesMergeGraphVariables() {
        OrchestrationGraphEntity entity = new OrchestrationGraphEntity();
        entity.setId("g-1");
        entity.setStatus("draft");
        entity.setOrchestrationMode("PIPELINE");
        entity.setGraphDefinition("{\"mode\":\"PIPELINE\",\"nodes\":[],\"edges\":[],"
                + "\"variables\":{\"modelProvider\":\"zhipu\",\"modelName\":\"glm-5.3-flash\",\"input\":\"模板占位\"}}");
        when(graphMapper.selectById("g-1")).thenReturn(entity);
        when(fourPhasePipeline.executeGraph(any(), any())).thenReturn(OrchestrationResult.success("e1", "ok"));

        service.executeGraph("g-1", "u1", "s1", "你好");

        ArgumentCaptor<OrchestrationContext> captor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(fourPhasePipeline).executeGraph(any(), captor.capture());
        assertEquals("zhipu", captor.getValue().getVariable("modelProvider"));
        assertEquals("glm-5.3-flash", captor.getValue().getVariable("modelName"));
        assertEquals("你好", captor.getValue().getVariable("input"));
    }
}
