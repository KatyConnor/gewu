package com.gewu.application.wenshi.reasoning;

import com.gewu.application.wenshi.reasoning.WenshiReasoningResult.PlanTree;
import com.gewu.application.wenshi.reasoning.WenshiReasoningResult.SubgoalNode;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Planner 单元测试。
 * <p>
 * 验证模板匹配逻辑和 LLM 分解回退行为。
 */
@ExtendWith(MockitoExtension.class)
class PlannerTest {

    @Mock private LlmClientFactory llmClientFactory;
    @Mock private LlmClient llmClient;

    @InjectMocks private Planner planner;

    private WenshiReasoningRequest buildRequest() {
        return WenshiReasoningRequest.builder()
                .message("test")
                .build();
    }

    @Test
    void plan_queryKeyword_shouldProduceKnowledgeLookupStrategy() {
        PlanTree plan = planner.plan("查询用户列表", buildRequest());

        assertNotNull(plan);
        assertFalse(plan.getSubgoals().isEmpty());
        assertTrue(plan.getSubgoals().stream()
                .anyMatch(s -> "KNOWLEDGE_LOOKUP".equals(s.getStrategy())),
                "查询类任务应包含 KNOWLEDGE_LOOKUP 策略");
    }

    @Test
    void plan_searchKeyword_shouldProduceKnowledgeLookupStrategy() {
        PlanTree plan = planner.plan("search user profiles", buildRequest());

        assertNotNull(plan);
        assertTrue(plan.getSubgoals().stream()
                .anyMatch(s -> "KNOWLEDGE_LOOKUP".equals(s.getStrategy())));
    }

    @Test
    void plan_analysisKeyword_shouldProduceToolExecutionStrategy() {
        PlanTree plan = planner.plan("分析销售数据", buildRequest());

        assertNotNull(plan);
        assertTrue(plan.getSubgoals().stream()
                .anyMatch(s -> "TOOL_EXECUTION".equals(s.getStrategy())),
                "分析类任务应包含 TOOL_EXECUTION 策略");
    }

    @Test
    void plan_executionKeyword_shouldProduceToolExecutionStrategy() {
        PlanTree plan = planner.plan("执行部署操作", buildRequest());

        assertNotNull(plan);
        assertTrue(plan.getSubgoals().stream()
                .anyMatch(s -> "TOOL_EXECUTION".equals(s.getStrategy())),
                "执行类任务应包含 TOOL_EXECUTION 策略");
    }

    @Test
    void plan_unknownTask_llmFails_fallsBackToSingleSubgoal() {
        when(llmClientFactory.getClient(any())).thenReturn(llmClient);
        when(llmClient.chat(any())).thenThrow(new RuntimeException("LLM unavailable"));

        PlanTree plan = planner.plan("讲一个笑话", buildRequest());

        assertNotNull(plan);
        assertEquals(1, plan.getSubgoals().size());
        SubgoalNode subgoal = plan.getSubgoals().get(0);
        assertEquals("LLM_REASONING", subgoal.getStrategy());
        assertEquals("讲一个笑话", subgoal.getDescription());
    }

    @Test
    void plan_unknownTask_llmReturnsJson_decomposesIntoMultipleSubgoals() {
        when(llmClientFactory.getClient(any())).thenReturn(llmClient);
        LlmResponse response = new LlmResponse();
        response.setContent("[{\"description\":\"理解笑话主题\",\"strategy\":\"KNOWLEDGE_LOOKUP\"}," +
                "{\"description\":\"生成笑话内容\",\"strategy\":\"LLM_REASONING\"}]");
        when(llmClient.chat(any())).thenReturn(response);

        PlanTree plan = planner.plan("讲一个笑话", buildRequest());

        assertNotNull(plan);
        assertEquals(2, plan.getSubgoals().size());
        assertEquals("KNOWLEDGE_LOOKUP", plan.getSubgoals().get(0).getStrategy());
        assertEquals("LLM_REASONING", plan.getSubgoals().get(1).getStrategy());
    }

    @Test
    void plan_allSubgoalsHaveIdsAndDescriptions() {
        PlanTree plan = planner.plan("查询并分析数据", buildRequest());

        assertNotNull(plan);
        for (SubgoalNode subgoal : plan.getSubgoals()) {
            assertNotNull(subgoal.getId(), "子目标应有 ID");
            assertNotNull(subgoal.getDescription(), "子目标应有描述");
            assertNotNull(subgoal.getStrategy(), "子目标应有策略");
        }
    }
}
