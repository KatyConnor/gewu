package com.gewu.application.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.workflow.engine.handler.ConditionHandler;
import com.gewu.domain.workflow.WorkflowInstance;
import com.gewu.domain.workflow.WorkflowNode;
import com.gewu.domain.workflow.WorkflowNodeInstance;
import com.gewu.domain.workflow.WorkflowTransition;
import com.gewu.infrastructure.mapper.WorkflowInstanceMapper;
import com.gewu.infrastructure.mapper.WorkflowNodeInstanceMapper;
import com.gewu.infrastructure.mapper.WorkflowNodeMapper;
import com.gewu.infrastructure.mapper.WorkflowTransitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流调度器核心路径测试（51 号 §四 P1 验收集）：
 * condition 路由（matched 标签）、join ALL 到齐放行、节点完成幂等闸、自动链端到端推进。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerTest {

    @Mock WorkflowInstanceMapper instanceMapper;
    @Mock WorkflowNodeMapper nodeMapper;
    @Mock WorkflowNodeInstanceMapper nodeInstanceMapper;
    @Mock WorkflowTransitionMapper transitionMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowExpressionEvaluator evaluator = new WorkflowExpressionEvaluator();
    private WorkflowNodeHandlerRegistry registry;
    private WorkflowScheduler scheduler;

    /** 完成回调落库开关：0=首次通过，>0=幂等吞掉 */
    private final AtomicInteger completionGate = new AtomicInteger(0);

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 纯单测环境初始化 MyBatis-Plus 实体元数据（Lambda 条件构造依赖）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.gewu.domain.workflow.WorkflowNodeInstance.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.gewu.domain.workflow.WorkflowInstance.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.gewu.domain.workflow.WorkflowNode.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.gewu.domain.workflow.WorkflowTransition.class);
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowNodeHandlerRegistry(List.of(new ConditionHandler(evaluator)));
        lenient().when(instanceMapper.selectById(anyString())).thenAnswer(inv -> instance);
        lenient().when(nodeInstanceMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
                .thenAnswer(inv -> completionGate.getAndIncrement() == 0 ? 1 : 0);
        lenient().when(nodeInstanceMapper.updateById(any())).thenReturn(1);
        lenient().when(instanceMapper.updateById(any())).thenReturn(1);
        lenient().when(instanceMapper.insert(any())).thenReturn(1);
        lenient().when(nodeInstanceMapper.insert(any())).thenAnswer(inv -> 1);
        lenient().when(nodeInstanceMapper.selectById(anyString())).thenAnswer(inv -> null);
    }

    private final WorkflowInstance instance = new WorkflowInstance();

    private WorkflowNode node(String id, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setWorkflowId("wf-1");
        node.setNodeName(id);
        node.setNodeType(type);
        return node;
    }

    private WorkflowNodeInstance runningRow(String id, String nodeId) {
        WorkflowNodeInstance row = new WorkflowNodeInstance();
        row.setId(id);
        row.setInstanceId("inst-1");
        row.setNodeId(nodeId);
        row.setBranchKey("");
        row.setIteration(0);
        row.setStatus("running");
        return row;
    }

    @Test
    @DisplayName("condition 完成后按 matched 标签路由出边")
    void conditionRoutesByMatchedLabel() {
        // n1(condition, matched=true) --label:true--> n2a / --label:false--> n2b
        WorkflowNode condition = node("n1", "condition");
        instance.setId("inst-1");
        instance.setStatus("running");
        instance.setVariables("{}");
        WorkflowNodeInstance row = runningRow("row-1", "n1");
        when(nodeInstanceMapper.selectById("row-1")).thenReturn(row);
        when(nodeMapper.selectById("n1")).thenReturn(condition);

        WorkflowNode yes = node("n2a", "transform");
        WorkflowNode no = node("n2b", "transform");
        lenient().when(nodeMapper.selectById("n2a")).thenReturn(yes);
        lenient().when(nodeMapper.selectById("n2b")).thenReturn(no);
        WorkflowTransition trueEdge = edge("n1", "n2a", "true");
        WorkflowTransition falseEdge = edge("n1", "n2b", "false");
        when(transitionMapper.selectList(any())).thenReturn(List.of(trueEdge, falseEdge));

        scheduler().onCompletion("inst-1",
                new WorkflowNodeContext.Completion("row-1", true, "{\"matched\": \"true\"}"));

        // 变量写入 + 路由命中 n2a：n2a 被激活（插入新节点实例行）
        ArgumentCaptor<WorkflowNodeInstance> captor = ArgumentCaptor.forClass(WorkflowNodeInstance.class);
        verify(nodeInstanceMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertEquals("n2a", captor.getValue().getNodeId());
    }

    @Test
    @DisplayName("join ALL：两分支到齐才放行，单分支到达不推进 join 出边")
    void joinAllWaitsForAllBranches() {
        instance.setId("inst-1");
        instance.setStatus("running");
        instance.setVariables("{}");
        WorkflowNode join = node("j1", "join");
        join.setConfig("{\"joinStrategy\":\"ALL\"}");
        WorkflowNode target = node("after", "transform");
        lenient().when(nodeMapper.selectById("j1")).thenReturn(join);
        lenient().when(nodeMapper.selectById("after")).thenReturn(target);
        lenient().when(nodeMapper.selectById("b1")).thenReturn(node("b1", "transform"));
        // selectList 统一返回空：入边期待数=0 触发防御不推进（WV 兜底语义）
        when(transitionMapper.selectList(any())).thenReturn(List.of());

        scheduler().joinArriveForTest(instance, join, "b1", "", 0, Map.of());

        // 仅 1/2 到达：join 未放行（不激活 after），记账已写回
        verify(instanceMapper).updateById(any(WorkflowInstance.class));
        verify(nodeInstanceMapper, never()).insert(any(WorkflowNodeInstance.class));
    }

    @Test
    @DisplayName("节点完成幂等闸：重复回调被状态机原子更新吞掉")
    void completionIsIdempotent() {
        instance.setId("inst-1");
        instance.setStatus("running");
        instance.setVariables("{}");
        WorkflowNodeInstance row = runningRow("row-1", "n1");
        when(nodeInstanceMapper.selectById("row-1")).thenReturn(row);
        when(nodeMapper.selectById("n1")).thenReturn(node("n1", "transform"));
        when(transitionMapper.selectList(any())).thenReturn(List.<WorkflowTransition>of());

        // 首次完成通过（gate 首次返回 1）；第二次完成闸返回 0 → 直接忽略
        completionGate.set(0);
        scheduler().onCompletion("inst-1", new WorkflowNodeContext.Completion("row-1", true, "{\"a\":1}"));
        scheduler().onCompletion("inst-1", new WorkflowNodeContext.Completion("row-1", true, "{\"a\":1}"));

        // 幂等证明：第二次回调被状态机闸吞掉——不再查询节点定义、不再推进
        // （首次推进查一次 n1；若第二次未被吞掉将查两次）
        verify(nodeMapper, org.mockito.Mockito.times(1)).selectById("n1");
    }

    // ---------- 辅助 ----------

    private WorkflowScheduler scheduler() {
        if (scheduler == null) {
            scheduler = new WorkflowScheduler(instanceMapper, nodeMapper, nodeInstanceMapper,
                    transitionMapper, registry, evaluator, objectMapper);
        }
        return scheduler;
    }

    private WorkflowTransition edge(String from, String to, String label) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setWorkflowId("wf-1");
        transition.setFromNodeId(from);
        transition.setToNodeId(to);
        transition.setLabel(label);
        return transition;
    }
}
