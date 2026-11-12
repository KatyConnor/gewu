package com.gewu.application.wenshi.evaluation;

import com.gewu.infrastructure.mapper.wenshi.SemanticFragmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link WenshiReevaluationTrigger} AE-P1-3 测试：开关/阈值闸门/单次武装/45 号预承诺规则判定。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Wenshi 复评自动触发器（AE-P1-3）")
class WenshiReevaluationTriggerTest {

    @Mock
    private SemanticFragmentMapper semanticFragmentMapper;
    @Mock
    private EvaluationRunner evaluationRunner;
    @Mock
    private ObjectProvider<SemanticFragmentMapper> mapperProvider;
    @Mock
    private ObjectProvider<EvaluationRunner> runnerProvider;

    private WenshiReevaluationTrigger trigger;

    @BeforeEach
    void setUp() {
        lenient().when(mapperProvider.getIfAvailable()).thenReturn(semanticFragmentMapper);
        lenient().when(runnerProvider.getIfAvailable()).thenReturn(evaluationRunner);
        trigger = new WenshiReevaluationTrigger(mapperProvider, runnerProvider);
        trigger.enabled = true;
        trigger.fragmentThreshold = 500L;
        trigger.baselines = "baseline,wenshi";
    }

    private ComparisonReport report(double baselineAvg, double wenshiAvg) {
        ComparisonReport.BaselineSummary baseline = new ComparisonReport.BaselineSummary();
        baseline.setBaselineName("baseline");
        baseline.setAvgScore(baselineAvg);
        ComparisonReport.BaselineSummary wenshi = new ComparisonReport.BaselineSummary();
        wenshi.setBaselineName("wenshi");
        wenshi.setAvgScore(wenshiAvg);
        return ComparisonReport.builder()
                .reportId("r-1")
                .summaries(Map.of("baseline", baseline, "wenshi", wenshi))
                .build();
    }

    @Test
    @DisplayName("总开关关闭：直接 disabled，不查知识库不跑评测")
    void disabledReturnsImmediately() {
        trigger.enabled = false;
        assertEquals("disabled", trigger.tryRun("manual"));
    }

    @Test
    @DisplayName("知识库片段低于阈值：below-threshold，不触发评测")
    void belowThresholdSkipsEvaluation() {
        when(semanticFragmentMapper.selectCount(any())).thenReturn(120L);
        String result = trigger.tryRun("manual");
        assertTrue(result.startsWith("below-threshold(120/500)"), result);
    }

    @Test
    @DisplayName("达标触发：Δ质量≥+5% → REEVAL_PASS（建议 D-6 接线评估）")
    void reevalPassWhenQualityGainAboveFivePercent() {
        when(semanticFragmentMapper.selectCount(any())).thenReturn(800L);
        doReturn(report(5.00, 5.40)).when(evaluationRunner).runComparison(anyList(), anyList()); // Δ=+8%

        String result = trigger.tryRun("manual");

        assertTrue(result.startsWith("REEVAL_PASS"), result);
        assertTrue(result.contains("D-6 接线评估"), result);
        // 单次闸门：第二次触发不再重复评测
        assertEquals("already-triggered", trigger.tryRun("manual"));
    }

    @Test
    @DisplayName("达标触发：Δ质量<+5% → REEVAL_HOLD（维持 legacy）")
    void reevalHoldWhenQualityGainBelowFivePercent() {
        when(semanticFragmentMapper.selectCount(any())).thenReturn(800L);
        doReturn(report(5.64, 5.61)).when(evaluationRunner).runComparison(anyList(), anyList()); // 45 号实测形态：Δ≈-0.5%

        String result = trigger.tryRun("scheduled");

        assertTrue(result.startsWith("REEVAL_HOLD"), result);
        assertTrue(result.contains("维持 legacy"), result);
    }

    @Test
    @DisplayName("评测执行失败：重新武装，下轮可重试")
    void failureRearmsForRetry() {
        when(semanticFragmentMapper.selectCount(any())).thenReturn(800L);
        // doThrow 家族桩定不经过 when() 调用链（避免与已武装桩交互）
        doThrow(new RuntimeException("LLM judge 不可用"))
                .when(evaluationRunner).runComparison(anyList(), anyList());

        String first = trigger.tryRun("manual");
        assertTrue(first.startsWith("error:"), first);

        // 重新武装后第二次可再次尝试（闸门已复位）
        doReturn(report(5.00, 5.30)).when(evaluationRunner).runComparison(anyList(), anyList());
        String second = trigger.tryRun("manual");
        assertTrue(second.startsWith("REEVAL_PASS"), second);
    }
}
