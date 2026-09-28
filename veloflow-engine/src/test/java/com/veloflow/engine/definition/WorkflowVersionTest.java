package com.veloflow.engine.definition;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.commons.VeloflowException;
import com.veloflow.engine.definition.dto.SaveWorkflowGraphCommand;
import com.veloflow.engine.definition.dto.WorkflowDTO;
import com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowTransitionMapper;
import com.veloflow.engine.persistence.mapper.WorkflowVersionMapper;
import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowTransition;
import com.veloflow.engine.persistence.model.WorkflowVersion;
import com.veloflow.engine.runtime.WorkflowDefinitionValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本快照与回滚测试（51 号 T4.4）：发布聚合递增、草稿保护、运行实例回滚保护、
 * 快照写回活表置草稿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowVersionTest {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowNodeMapper workflowNodeMapper;
    @Mock WorkflowTransitionMapper workflowTransitionMapper;
    @Mock WorkflowInstanceMapper workflowInstanceMapper;
    @Mock WorkflowVersionMapper versionMapper;
    @Mock WorkflowDefinitionValidator definitionValidator;

    private WorkflowService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Workflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowNode.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTransition.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowInstance.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowMapper, workflowNodeMapper, workflowTransitionMapper,
                workflowInstanceMapper, versionMapper, definitionValidator);
        lenient().when(definitionValidator.validate(any())).thenReturn(List.of());
    }

    private Workflow draftWorkflow() {
        Workflow wf = new Workflow();
        wf.setId("wf-1");
        wf.setWorkflowName("测试流程");
        wf.setStatus(0);
        wf.setWorkflowVersion(0);
        return wf;
    }

    private WorkflowNode node(String bizId, String type) {
        WorkflowNode n = new WorkflowNode();
        n.setId("row-" + bizId);
        n.setBizNodeId(bizId);
        n.setWorkflowId("wf-1");
        n.setNodeName(bizId);
        n.setNodeType(type);
        n.setSortOrder(0);
        return n;
    }

    private WorkflowTransition edge(String fromRow, String toRow) {
        WorkflowTransition t = new WorkflowTransition();
        t.setId("tr-1");
        t.setWorkflowId("wf-1");
        t.setFromNodeId(fromRow);
        t.setToNodeId(toRow);
        return t;
    }

    @Test
    @DisplayName("发布即快照：聚合图结构、版本号 1、workflow_version 同步")
    void publishAggregatesSnapshotAndIncrements() {
        Workflow wf = draftWorkflow();
        when(workflowMapper.selectById("wf-1")).thenReturn(wf);
        when(workflowNodeMapper.selectList(any())).thenReturn(List.of(node("t1", "manual-trigger"), node("fin", "return")));
        when(workflowTransitionMapper.selectList(any())).thenReturn(List.of(edge("row-t1", "row-fin")));
        when(versionMapper.selectList(any())).thenReturn(List.of());

        WorkflowDTO dto = service.publishWorkflow("wf-1");

        assertEquals(1, dto.getWorkflowVersion());
        ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getVersion());
        // 快照统一 biz 坐标系：流转端点为 biz id 而非行 ID
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().getDefinitionSnapshot().contains("\"fromNodeId\":\"t1\""));
    }

    @Test
    @DisplayName("二次发布版本号递增为 2")
    void secondPublishIncrements() {
        Workflow wf = draftWorkflow();
        wf.setWorkflowVersion(1);
        when(workflowMapper.selectById("wf-1")).thenReturn(wf);
        when(workflowNodeMapper.selectList(any())).thenReturn(List.of(node("t1", "manual-trigger")));
        when(workflowTransitionMapper.selectList(any())).thenReturn(List.of());
        WorkflowVersion prev = new WorkflowVersion();
        prev.setVersion(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(prev));

        WorkflowDTO dto = service.publishWorkflow("wf-1");

        assertEquals(2, dto.getWorkflowVersion());
        ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
    }

    @Test
    @DisplayName("草稿保护：已发布流程保存图被拒（41001 段）")
    void saveGraphRejectedWhenPublished() {
        Workflow wf = draftWorkflow();
        wf.setStatus(1);
        when(workflowMapper.selectById("wf-1")).thenReturn(wf);

        VeloflowException ex = assertThrows(VeloflowException.class,
                () -> service.saveWorkflowGraph("wf-1", new SaveWorkflowGraphCommand()));
        assertEquals(VeloflowErrorCode.FLOW_INVALID_STATE, ex.getCode());
        verify(workflowNodeMapper, never()).delete(any());
    }

    @Test
    @DisplayName("回滚保护：存在运行中实例时拒绝")
    void rollbackBlockedByRunningInstance() {
        Workflow wf = draftWorkflow();
        wf.setStatus(1);
        wf.setWorkflowVersion(1);
        when(workflowMapper.selectById("wf-1")).thenReturn(wf);
        when(workflowInstanceMapper.selectCount(any())).thenReturn(2L);

        VeloflowException ex = assertThrows(VeloflowException.class,
                () -> service.rollbackToVersion("wf-1", 1));
        assertEquals(VeloflowErrorCode.FLOW_INVALID_STATE, ex.getCode());
        verify(workflowMapper, never()).updateById(any(Workflow.class));
    }

    @Test
    @DisplayName("回滚：快照写回活表（biz 身份）、流程置回草稿")
    void rollbackRestoresSnapshotAsDraft() throws Exception {
        Workflow wf = draftWorkflow();
        wf.setStatus(1);
        wf.setWorkflowVersion(1);
        when(workflowMapper.selectById("wf-1")).thenReturn(wf);
        when(workflowInstanceMapper.selectCount(any())).thenReturn(0L);
        // 快照（biz 坐标系）
        String snapshot = "{\"nodes\":[{\"nodeId\":\"t1\",\"nodeName\":\"t1\",\"nodeType\":\"manual-trigger\",\"config\":\"{}\",\"sortOrder\":0}],\"transitions\":[]}";
        WorkflowVersion v = new WorkflowVersion();
        v.setWorkflowId("wf-1");
        v.setVersion(1);
        v.setDefinitionSnapshot(snapshot);
        when(versionMapper.selectOne(any())).thenReturn(v);
        when(workflowNodeMapper.selectList(any())).thenReturn(List.of());
        when(workflowTransitionMapper.selectList(any())).thenReturn(List.of());

        WorkflowDTO dto = service.rollbackToVersion("wf-1", 1);

        assertEquals(0, dto.getStatus());
        verify(workflowNodeMapper).delete(any());
        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeMapper).insert(nodeCaptor.capture());
        assertEquals("t1", nodeCaptor.getValue().getBizNodeId());
    }
}
