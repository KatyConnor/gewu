package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排图删除级联清理回归测试（P1 存储闭环）：
 * 删除编排图必须级联清理其执行实例与审批请求（均逻辑删除）。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationGraphCascadeTest {

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
    @DisplayName("删除编排图级联清理执行实例与审批请求")
    void deleteGraphCascades() {
        OrchestrationExecutionEntity execution = new OrchestrationExecutionEntity();
        execution.setId("exec-1");
        execution.setGraphId("g-1");
        when(executionMapper.selectList(any())).thenReturn(List.of(execution));

        service.deleteGraph("g-1");

        verify(approvalMapper).delete(any());
        verify(executionMapper).delete(any());
        verify(graphMapper).deleteById("g-1");
    }
}
