package com.gewu.application.evaluation;

import com.gewu.application.wenshi.evaluation.ComparisonReport;
import com.gewu.application.wenshi.evaluation.EvaluationRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * {@link QualityBaselineRunner} P4-D 测试：棘轮只紧不松/容差回归/首轮热身/开关。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("质量基准棘轮（P4-D）")
class QualityBaselineRunnerTest {

    @Mock
    private EvaluationRunner evaluationRunner;
    @Mock
    private ObjectProvider<EvaluationRunner> runnerProvider;

    @TempDir
    Path tempDir;

    private QualityBaselineRunner runner() {
        QualityBaselineRunner r = new QualityBaselineRunner(runnerProvider);
        r.enabled = true;
        r.baselines = "baseline,wenshi";
        r.tolerance = 0.05;
        r.outputDir = tempDir.toString();
        return r;
    }

    private ComparisonReport report(double baselineAvg, double wenshiAvg) {
        ComparisonReport.BaselineSummary b = new ComparisonReport.BaselineSummary();
        b.setBaselineName("baseline");
        b.setAvgScore(baselineAvg);
        ComparisonReport.BaselineSummary w = new ComparisonReport.BaselineSummary();
        w.setBaselineName("wenshi");
        w.setAvgScore(wenshiAvg);
        return ComparisonReport.builder().reportId("r-1")
                .summaries(Map.of("baseline", b, "wenshi", w)).build();
    }

    private void writeBaseline(double baselineAvg, double wenshiAvg) throws Exception {
        ComparisonReport r = report(baselineAvg, wenshiAvg);
        String json = "{\"summaries\":{" 
                + "\"baseline\":{\"baselineName\":\"baseline\",\"avgScore\":" + baselineAvg + "},"
                + "\"wenshi\":{\"baselineName\":\"wenshi\",\"avgScore\":" + wenshiAvg + "}}}";
        Files.writeString(Path.of(tempDir.toString(), "quality-baseline.json"), json);
    }

    @Test
    @DisplayName("总开关关闭：直接 disabled")
    void disabledReturnsImmediately() {
        QualityBaselineRunner r = runner();
        r.enabled = false;
        assertEquals("disabled", r.tryRun("manual"));
    }

    @Test
    @DisplayName("首轮（无基准文件）：no-baseline 热身放行，gate-state 记 PASS")
    void firstRunIsWarmup() throws Exception {
        QualityBaselineRunner r = runner();
        when(runnerProvider.getIfAvailable()).thenReturn(evaluationRunner);
        doReturn(report(5.64, 5.61)).when(evaluationRunner).runComparison(anyList(), anyList());

        String result = r.tryRun("manual");

        assertTrue(result.startsWith("no-baseline"), result);
        File gate = Path.of(tempDir.toString(), "gate-state.json").toFile();
        assertTrue(gate.exists());
        assertTrue(Files.readString(gate.toPath()).contains("\"PASS\""));
    }

    @Test
    @DisplayName("容差内回归：PASS 且棘轮不松（旧基准保持）")
    void withinToleranceKeepsOldBaseline() throws Exception {
        writeBaseline(5.64, 5.61);
        QualityBaselineRunner r = runner();
        when(runnerProvider.getIfAvailable()).thenReturn(evaluationRunner);
        doReturn(report(5.50, 5.50)).when(evaluationRunner).runComparison(anyList(), anyList()); // 跌 2.5%

        String result = r.tryRun("manual");

        assertEquals("PASS", result);
        // 棘轮不松：基准文件仍为旧值 5.64
        String baseline = Files.readString(Path.of(tempDir.toString(), "quality-baseline.json"));
        assertTrue(baseline.contains("5.64"), baseline);
    }

    @Test
    @DisplayName("跌幅超容差：REGRESS + gate-state 置 FAIL（CI 阻断）")
    void regressionBeyondToleranceFailsGate() throws Exception {
        writeBaseline(5.64, 5.61);
        QualityBaselineRunner r = runner();
        when(runnerProvider.getIfAvailable()).thenReturn(evaluationRunner);
        doReturn(report(4.50, 4.40)).when(evaluationRunner).runComparison(anyList(), anyList()); // 跌 ~20%

        String result = r.tryRun("manual");

        assertTrue(result.startsWith("REGRESS"), result);
        File gate = Path.of(tempDir.toString(), "gate-state.json").toFile();
        assertTrue(Files.readString(gate.toPath()).contains("\"FAIL\""));
    }

    @Test
    @DisplayName("评分提升：棘轮上调基准（只紧不松）")
    void improvementTightensBaseline() throws Exception {
        writeBaseline(5.64, 5.61);
        QualityBaselineRunner r = runner();
        when(runnerProvider.getIfAvailable()).thenReturn(evaluationRunner);
        doReturn(report(5.90, 5.90)).when(evaluationRunner).runComparison(anyList(), anyList());

        String result = r.tryRun("manual");

        assertEquals("PASS", result);
        String baseline = Files.readString(Path.of(tempDir.toString(), "quality-baseline.json"));
        assertTrue(baseline.contains("5.9"), "基准应上调至新值: " + baseline);
    }
}
