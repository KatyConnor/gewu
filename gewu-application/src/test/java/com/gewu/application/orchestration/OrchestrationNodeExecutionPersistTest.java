package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点执行记录幂等落库测试（docs/design/47 问题四）：
 * node_start 建 RUNNING 记录，node_complete 推进 SUCCEEDED 并计算耗时；
 * 同一节点先 start 后 complete 时复用同一条记录（upsert 而非重复插入）。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationNodeExecutionPersistTest {

    @Mock OrchestrationEngine orchestrationEngine;
    @Mock OrchestrationGraphMapper graphMapper;
    @Mock OrchestrationExecutionMapper executionMapper;
    @Mock ApprovalRequestMapper approvalMapper;
    @Mock com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    @Mock com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;
    @Mock OrchestrationNodeExecutionMapper nodeExecutionMapper;

    private OrchestrationService service;
    /** 内存存储：模拟按 executionId+nodeId 的 upsert 目标 */
    private final List<OrchestrationNodeExecutionEntity> store = new ArrayList<>();
    /** insert 时刻的状态快照（独立于内存对象，验证落库瞬间的值） */
    private final List<String> insertStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(orchestrationEngine, graphMapper, executionMapper,
                approvalMapper, new ObjectMapper(), fourPhasePipeline, orchestrationTracer,
                new GraphDefinitionValidator(new ObjectMapper()), nodeExecutionMapper);
        when(nodeExecutionMapper.insert(any())).thenAnswer(inv -> {
            // 模拟持久化：记录 insert 时刻的状态快照
            OrchestrationNodeExecutionEntity source = inv.getArgument(0);
            insertStatuses.add(source.getStatus());
            store.add(source);
            return 1;
        });
        when(nodeExecutionMapper.selectList(any())).thenAnswer(inv -> List.copyOf(store));
        when(nodeExecutionMapper.updateById(any())).thenAnswer(inv -> 1);
    }

    @Test
    @DisplayName("流式执行落节点记录：start 建 RUNNING，complete 复用记录推进 SUCCEEDED")
    void streamPersistsNodeExecutions() {
        OrchestrationGraphEntity graphEntity = new OrchestrationGraphEntity();
        graphEntity.setId("g-1");
        graphEntity.setStatus("draft");
        graphEntity.setOrchestrationMode("PIPELINE");
        graphEntity.setGraphDefinition("{\"mode\":\"PIPELINE\",\"nodes\":["
                + "{\"nodeId\":\"n1\",\"type\":\"AGENT\",\"roleCode\":\"DEVELOPER\"}],\"edges\":[]}");
        when(graphMapper.selectById("g-1")).thenReturn(graphEntity);
        when(orchestrationEngine.executeStream(any(), any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEvent.NODE_START).nodeId("n1").build(),
                AgentEvent.builder().type(AgentEvent.NODE_COMPLETE).nodeId("n1")
                        .metadata(java.util.Map.of("outputLen", 3)).build()));

        service.executeGraphStream("g-1", "u1", "s1", "输入").blockLast();

        // node_start 建 RUNNING 记录（insert 恰好一次，落库瞬间状态为 RUNNING）
        assertEquals(1, store.size());
        assertEquals("RUNNING", insertStatuses.get(0));
        OrchestrationNodeExecutionEntity inserted = store.get(0);
        assertEquals("n1", inserted.getNodeId());
        assertEquals("AGENT", inserted.getNodeType());
        assertEquals("DEVELOPER", inserted.getRoleCode());

        // node_complete 复用同一条记录推进终态（updateById 而非重复 insert）
        ArgumentCaptor<OrchestrationNodeExecutionEntity> captor =
                ArgumentCaptor.forClass(OrchestrationNodeExecutionEntity.class);
        verify(nodeExecutionMapper, times(1)).updateById(captor.capture());
        OrchestrationNodeExecutionEntity updated = captor.getValue();
        assertEquals("SUCCEEDED", updated.getStatus());
        assertNotNull(updated.getCompletedAt());
        assertNotNull(updated.getDurationMs());
    }

    @Test
    @DisplayName("error 事件将节点记录推进 FAILED")
    void errorEventMarksNodeFailed() {
        OrchestrationGraphEntity graphEntity = new OrchestrationGraphEntity();
        graphEntity.setId("g-3");
        graphEntity.setStatus("draft");
        graphEntity.setOrchestrationMode("PIPELINE");
        graphEntity.setGraphDefinition("{\"mode\":\"PIPELINE\",\"nodes\":["
                + "{\"nodeId\":\"n1\",\"type\":\"AGENT\"}],\"edges\":[]}");
        when(graphMapper.selectById("g-3")).thenReturn(graphEntity);
        when(orchestrationEngine.executeStream(any(), any())).thenReturn(Flux.just(
                AgentEvent.builder().type(AgentEvent.NODE_START).nodeId("n1").build(),
                AgentEvent.builder().type(AgentEvent.ERROR).nodeId("n1").errorMessage("boom").build()));

        service.executeGraphStream("g-3", "u1", "s1", "输入").blockLast();

        assertEquals(1, store.size());
        verify(nodeExecutionMapper).updateById(any(OrchestrationNodeExecutionEntity.class));
        assertEquals("FAILED", store.get(0).getStatus());
        assertEquals("boom", store.get(0).getErrorMessage());
    }
}
