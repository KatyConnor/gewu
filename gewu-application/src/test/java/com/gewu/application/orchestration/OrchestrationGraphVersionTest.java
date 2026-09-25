package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.domain.orchestration.OrchestrationGraphVersionEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排图版本化测试（WFO-01/02，EXEPLAN-ORCH-2026-09）：
 * 激活发布不可变版本快照、执行记录绑定版本引用、下架回滚草稿、回滚写回历史定义。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationGraphVersionTest {

    private static final String DEFINITION =
            "{\"mode\":\"PIPELINE\",\"nodes\":[{\"nodeId\":\"n1\",\"type\":\"AGENT\",\"roleCode\":\"DEVELOPER\"}],\"edges\":[]}";

    @Mock OrchestrationEngine orchestrationEngine;
    @Mock OrchestrationGraphMapper graphMapper;
    @Mock OrchestrationExecutionMapper executionMapper;
    @Mock ApprovalRequestMapper approvalMapper;
    @Mock com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    @Mock com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;
    @Mock com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper nodeExecutionMapper;
    @Mock OrchestrationGraphVersionMapper versionMapper;
    @Mock com.gewu.infrastructure.mapper.OrchestrationScheduleMapper scheduleMapper;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(orchestrationEngine, graphMapper, executionMapper,
                approvalMapper, new ObjectMapper(), fourPhasePipeline, orchestrationTracer,
                new GraphDefinitionValidator(new ObjectMapper(), graphMapper), nodeExecutionMapper, versionMapper, scheduleMapper);
    }

    private OrchestrationGraphEntity draftGraph() {
        OrchestrationGraphEntity entity = new OrchestrationGraphEntity();
        entity.setId("g-1");
        entity.setStatus("draft");
        entity.setOrchestrationMode("PIPELINE");
        entity.setVersion("1");
        entity.setGraphDefinition(DEFINITION);
        return entity;
    }

    @Test
    @DisplayName("激活发布版本快照：首次激活产生 v1，图版本号同步推进")
    void activatePublishesVersionSnapshot() {
        OrchestrationGraphEntity entity = draftGraph();
        when(graphMapper.selectById("g-1")).thenReturn(entity);
        when(versionMapper.selectList(any())).thenReturn(List.of());

        service.activateGraph("g-1", "u1");

        ArgumentCaptor<OrchestrationGraphVersionEntity> captor =
                ArgumentCaptor.forClass(OrchestrationGraphVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        OrchestrationGraphVersionEntity snapshot = captor.getValue();
        assertEquals("g-1", snapshot.getGraphId());
        assertEquals(1, snapshot.getVersion());
        assertEquals(DEFINITION, snapshot.getGraphDefinition());
        assertEquals("PIPELINE", snapshot.getOrchestrationMode());
        // 图实体同步推进状态与版本号
        assertEquals("active", entity.getStatus());
        assertEquals("1", entity.getVersion());
    }

    @Test
    @DisplayName("再次激活版本号自增（已有 v1 时产生 v2）")
    void activateIncrementsVersion() {
        OrchestrationGraphEntity entity = draftGraph();
        entity.setStatus("draft");
        when(graphMapper.selectById("g-1")).thenReturn(entity);
        OrchestrationGraphVersionEntity v1 = new OrchestrationGraphVersionEntity();
        v1.setGraphId("g-1");
        v1.setVersion(1);
        v1.setGraphDefinition(DEFINITION);
        when(versionMapper.selectList(any())).thenReturn(List.of(v1));

        service.activateGraph("g-1", "u1");

        ArgumentCaptor<OrchestrationGraphVersionEntity> captor =
                ArgumentCaptor.forClass(OrchestrationGraphVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
        assertEquals("2", entity.getVersion());
    }

    @Test
    @DisplayName("active 图执行优先加载版本快照，执行记录绑定 versionId 与触发方式")
    void executionBindsVersionSnapshot() {
        OrchestrationGraphEntity entity = draftGraph();
        entity.setStatus("active");
        when(graphMapper.selectById("g-1")).thenReturn(entity);
        OrchestrationGraphVersionEntity v1 = new OrchestrationGraphVersionEntity();
        v1.setId("ver-1");
        v1.setGraphId("g-1");
        v1.setVersion(1);
        v1.setGraphDefinition(DEFINITION);
        when(versionMapper.selectList(any())).thenReturn(List.of(v1));
        when(fourPhasePipeline.executeGraph(any(), any()))
                .thenReturn(OrchestrationResult.success("e1", "ok"));

        service.executeGraph("g-1", "u1", "s1", "输入");

        ArgumentCaptor<OrchestrationExecutionEntity> captor =
                ArgumentCaptor.forClass(OrchestrationExecutionEntity.class);
        verify(executionMapper).insert(captor.capture());
        assertEquals("ver-1", captor.getValue().getVersionId());
        assertEquals("MANUAL", captor.getValue().getTriggerType());
    }

    @Test
    @DisplayName("下架：active -> draft，可重新编辑")
    void deactivatesActiveGraph() {
        OrchestrationGraphEntity entity = draftGraph();
        entity.setStatus("active");
        when(graphMapper.selectById("g-1")).thenReturn(entity);

        service.deactivateGraph("g-1", "u1");

        assertEquals("draft", entity.getStatus());
        verify(graphMapper).updateById(entity);
    }

    @Test
    @DisplayName("回滚：版本快照写回草稿定义（mode 规范化注入）")
    void rollbackRestoresVersionDefinition() {
        OrchestrationGraphEntity entity = draftGraph();
        entity.setGraphDefinition("{\"nodes\":[{\"nodeId\":\"n1\",\"type\":\"AGENT\"}],\"edges\":[]}");
        when(graphMapper.selectById("g-1")).thenReturn(entity);
        OrchestrationGraphVersionEntity snapshot = new OrchestrationGraphVersionEntity();
        snapshot.setId("ver-9");
        snapshot.setGraphId("g-1");
        snapshot.setVersion(9);
        snapshot.setOrchestrationMode("PIPELINE");
        snapshot.setGraphDefinition(DEFINITION);
        when(versionMapper.selectById("ver-9")).thenReturn(snapshot);

        OrchestrationGraphEntity restored = service.rollbackGraphVersion("g-1", "ver-9", "u1");

        assertEquals(DEFINITION, restored.getGraphDefinition());
        verify(graphMapper).updateById(entity);
    }

    @Test
    @DisplayName("active 图回滚被拒绝（需先下架）")
    void rollbackRejectedWhenActive() {
        OrchestrationGraphEntity entity = draftGraph();
        entity.setStatus("active");
        when(graphMapper.selectById("g-1")).thenReturn(entity);

        try {
            service.rollbackGraphVersion("g-1", "ver-9", "u1");
            org.junit.jupiter.api.Assertions.fail("active 图回滚应被拒绝");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("下架"));
        }
    }
}
