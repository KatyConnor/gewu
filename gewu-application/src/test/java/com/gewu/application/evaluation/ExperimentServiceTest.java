package com.gewu.application.evaluation;

import com.gewu.infrastructure.dto.ExperimentGroupStats;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ExperimentService} A/B 对比报表测试（T3.4）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A/B 实验对比服务")
class ExperimentServiceTest {

    @Mock
    private AgentExecutionMapper agentExecutionMapper;

    @InjectMocks
    private ExperimentService experimentService;

    private ExperimentGroupStats stats(String group, long total, long success) {
        return ExperimentGroupStats.builder()
                .experimentGroup(group)
                .totalCount(total)
                .successCount(success)
                .avgDurationMs(1500.0)
                .avgTokens(800.0)
                .avgJudgeScore(0.85)
                .judgedCount(10L)
                .build();
    }

    @Test
    @DisplayName("对比报表：分组统计 + 成功率派生 + 预置组说明")
    void compareReport() {
        when(agentExecutionMapper.aggregateByExperimentGroup(1000L, 2000L)).thenReturn(List.of(
                stats("baseline", 100, 80),
                stats("full_stack", 50, 45)));

        Map<String, Object> report = experimentService.compare(1000L, 2000L);

        @SuppressWarnings("unchecked")
        Map<String, ExperimentGroupStats> groups = (Map<String, ExperimentGroupStats>) report.get("groups");
        assertEquals(2, groups.size());
        assertEquals(0.8, groups.get("baseline").getSuccessRate(), 0.001);
        assertEquals(0.9, groups.get("full_stack").getSuccessRate(), 0.001);
        assertEquals(0.85, groups.get("baseline").getAvgJudgeScore());

        // 预置对照组说明存在
        @SuppressWarnings("unchecked")
        Map<String, String> presets = (Map<String, String>) report.get("presetGroups");
        assertEquals(4, presets.size());
        assertTrue(presets.containsKey("baseline"));
        assertTrue(presets.containsKey("full_stack"));
        // 统计建议存在
        assertTrue(report.containsKey("sampleAdvice"));
    }

    @Test
    @DisplayName("零样本分组被过滤；时间窗口透传")
    void zeroSampleGroupsFiltered() {
        when(agentExecutionMapper.aggregateByExperimentGroup(null, null)).thenReturn(List.of(
                stats("ghost", 0, 0),
                stats("baseline", 10, 10)));

        Map<String, Object> report = experimentService.compare(null, null);

        @SuppressWarnings("unchecked")
        Map<String, ExperimentGroupStats> groups = (Map<String, ExperimentGroupStats>) report.get("groups");
        assertEquals(1, groups.size());
        assertFalse(groups.containsKey("ghost"));
        assertEquals(1.0, groups.get("baseline").getSuccessRate(), 0.001);
    }
}
