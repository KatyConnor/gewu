package com.gewu.application.wenshi.reasoning;

import com.gewu.application.wenshi.reasoning.SolverRouter.Strategy;
import com.gewu.application.wenshi.reasoning.WenshiReasoningRequest.ReasoningConstraints;
import com.gewu.application.wenshi.reasoning.WenshiReasoningResult.SubgoalNode;
import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SolverRouter 单元测试。
 * <p>
 * 验证策略路由优先级：EXPERIENCE_REUSE -> KNOWLEDGE_LOOKUP -> TOOL_EXECUTION -> LLM_REASONING。
 */
@ExtendWith(MockitoExtension.class)
class SolverRouterTest {

    @Mock private ExperienceMapper experienceMapper;
    @Mock private EmbeddingAdapter embeddingAdapter;

    @InjectMocks private SolverRouter router;

    private WenshiReasoningRequest buildRequest(boolean enableExperience) {
        return WenshiReasoningRequest.builder()
                .message("test")
                .tenantId("tenant-1")
                .constraints(ReasoningConstraints.builder()
                        .enableExperience(enableExperience)
                        .build())
                .build();
    }

    private SubgoalNode subgoal(String strategy) {
        return SubgoalNode.builder()
                .id("s1").description("test subgoal").strategy(strategy)
                .dependencies(List.of()).build();
    }

    @Test
    void selectStrategy_knowledgeLookupSubgoal_returnsKnowledgeLookup() {
        Strategy result = router.selectStrategy(subgoal("KNOWLEDGE_LOOKUP"), buildRequest(false));
        assertEquals(Strategy.KNOWLEDGE_LOOKUP, result);
    }

    @Test
    void selectStrategy_toolExecutionSubgoal_returnsToolExecution() {
        Strategy result = router.selectStrategy(subgoal("TOOL_EXECUTION"), buildRequest(false));
        assertEquals(Strategy.TOOL_EXECUTION, result);
    }

    @Test
    void selectStrategy_unknownStrategy_returnsLlmReasoning() {
        Strategy result = router.selectStrategy(subgoal("UNKNOWN"), buildRequest(false));
        assertEquals(Strategy.LLM_REASONING, result);
    }

    @Test
    void selectStrategy_nullStrategy_returnsLlmReasoning() {
        Strategy result = router.selectStrategy(subgoal(null), buildRequest(false));
        assertEquals(Strategy.LLM_REASONING, result);
    }

    @Test
    void selectStrategy_experienceEnabledButNoMatch_returnsNextStrategy() {
        when(embeddingAdapter.embed(anyString())).thenReturn(new float[]{0.1f});
        when(experienceMapper.searchByVector(anyString(), anyString(), anyInt())).thenReturn(List.of());

        Strategy result = router.selectStrategy(subgoal("KNOWLEDGE_LOOKUP"), buildRequest(true));
        assertEquals(Strategy.KNOWLEDGE_LOOKUP, result);
    }

    @Test
    void selectStrategy_experienceHitWithHighScore_returnsExperienceReuse() {
        Experience exp = new Experience();
        exp.setScore(new BigDecimal("0.85"));
        exp.setScenario("similar task");
        when(embeddingAdapter.embed(anyString())).thenReturn(new float[]{0.1f});
        when(experienceMapper.searchByVector(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(exp));

        Strategy result = router.selectStrategy(subgoal("LLM_REASONING"), buildRequest(true));
        assertEquals(Strategy.EXPERIENCE_REUSE, result);
    }

    @Test
    void selectStrategy_experienceHitWithLowScore_returnsNextStrategy() {
        Experience exp = new Experience();
        exp.setScore(new BigDecimal("0.50"));
        when(embeddingAdapter.embed(anyString())).thenReturn(new float[]{0.1f});
        when(experienceMapper.searchByVector(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(exp));

        Strategy result = router.selectStrategy(subgoal("KNOWLEDGE_LOOKUP"), buildRequest(true));
        assertEquals(Strategy.KNOWLEDGE_LOOKUP, result);
    }

    @Test
    void selectStrategy_experienceQueryFails_returnsNextStrategy() {
        when(embeddingAdapter.embed(anyString())).thenThrow(new RuntimeException("embedding down"));

        Strategy result = router.selectStrategy(subgoal("KNOWLEDGE_LOOKUP"), buildRequest(true));
        assertEquals(Strategy.KNOWLEDGE_LOOKUP, result);
    }
}
