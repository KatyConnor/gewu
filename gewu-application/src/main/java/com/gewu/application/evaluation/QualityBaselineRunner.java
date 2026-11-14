package com.gewu.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.wenshi.evaluation.ComparisonReport;
import com.gewu.application.wenshi.evaluation.EvaluationRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量基准棘轮（P4-D，方案 B：定时跑批 + 基准文件 + PR 本地比对，零 LLM 门禁）。
 * <p>机制（AE-PLAN 阶段4 P4-D）：定时执行 45 号同题集对比评测
 * （{@link EvaluationRunner#runComparison}）→ 结果写 {@code latest-run.json} →
 * 与 {@code baseline.json} 基准比对（棘轮：评分均值跌幅超容差即 REGRESS，
 * 涨幅或持平仅上调基准——棘轮只紧不松）→ 门禁状态写 {@code gate-state.json}，
 * CI（Jenkins/脚本）读取该文件判定阻断。PR/合并门禁零 LLM 成本。
 * <p>配置（默认关闭）：
 * <pre>
 * gewu.evaluation.quality-gate:
 *   enabled: false                # 灌入基线跑批就绪后开启
 *   baselines: baseline,wenshi    # 对比分组
 *   tolerance: 0.05               # 允许跌幅容差（45 号预承诺口径）
 *   output-dir: tests/quality     # 基准/最新/门禁文件目录
 *   cron: "0 40 3 * * *"          # 跑批时刻（在 reeval 3:30 之后）
 * </pre>
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@Slf4j
@Component
public class QualityBaselineRunner {

    private final ObjectProvider<EvaluationRunner> evaluationRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gewu.evaluation.quality-gate.enabled:false}")
    boolean enabled;
    @Value("${gewu.evaluation.quality-gate.baselines:baseline,wenshi}")
    String baselines;
    @Value("${gewu.evaluation.quality-gate.tolerance:0.05}")
    double tolerance;
    @Value("${gewu.evaluation.quality-gate.output-dir:tests/quality}")
    String outputDir;
    @Value("${gewu.evaluation.quality-gate.cron:0 40 3 * * *}")
    String cron;
    /** 首跑开关（P4-D 首跑）：应用就绪即执行一次跑批（本地/环境热身；日常用定时与开关控制） */
    @Value("${gewu.evaluation.quality-gate.first-run-on-startup:false}")
    boolean firstRunOnStartup;

    public QualityBaselineRunner(ObjectProvider<EvaluationRunner> evaluationRunner) {
        this.evaluationRunner = evaluationRunner;
    }

    /** P4-D 首跑入口：应用就绪且 first-run-on-startup=true 时执行一次（产出 latest-run/gate-state 供人工确认固化） */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void firstRunOnReady() {
        if (firstRunOnStartup) {
            String result = tryRun("startup-first-run");
            log.info("P4-D 首跑（startup-first-run）: {}", result);
        }
    }

    @Scheduled(cron = "${gewu.evaluation.quality-gate.cron:0 40 3 * * *}")
    public void scheduledQualityBatch() {
        tryRun("scheduled");
    }

    /**
     * 执行一次质量跑批与棘轮比对（手动触发入口与定时共用）。
     *
     * @return 结果一句话：disabled / infrastructure-unavailable / no-baseline（首轮热身）/ PASS /
     *         REGRESS(...)（评分跌破容差，CI 阻断）/ error(...)
     */
    public synchronized String tryRun(String source) {
        if (!enabled) {
            return "disabled";
        }
        EvaluationRunner runner = evaluationRunner.getIfAvailable();
        if (runner == null) {
            return "infrastructure-unavailable";
        }
        try {
            List<String> baselineList = Arrays.stream(baselines.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            log.info("质量跑批（来源={}）：执行 45 号同题集对比评测 baselines={}", source, baselineList);
            ComparisonReport report = runner.runComparison(baselineList, List.of());

            File dir = new File(outputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                return "error: 无法创建输出目录 " + outputDir;
            }
            Map<String, Object> latest = summaryOf(report);
            latest.put("generatedAt", Instant.now().toString());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(dir, "latest-run.json"), latest);

            File baselineFile = new File(dir, "quality-baseline.json");
            if (!baselineFile.exists()) {
                String verdict = "no-baseline（首轮热身：请人工确认本轮结果合理后，"
                        + "将 latest-run.json 的 summaries 内容固化入 quality-baseline.json）";
                writeGateState(dir, verdict, latest, Map.of());
                log.info("质量跑批（P4-D）: {}", verdict);
                return verdict;
            }

            Map<String, Object> baseline = objectMapper.readValue(baselineFile, Map.class);
            Map<String, Object> regressions = ratchetCompare(baseline, latest);
            String verdict = regressions.isEmpty() ? "PASS" : "REGRESS: " + regressions;
            writeGateState(dir, verdict, latest, regressions);

            if (regressions.isEmpty()) {
                // 棘轮只紧不松：仅当某基线评分高于旧基准时上调（跌幅/持平保持旧基准）
                Map<String, Object> next = new LinkedHashMap<>(baseline);
                Map<String, Object> nextSummaries =
                        new LinkedHashMap<>((Map<String, Object>) next.getOrDefault("summaries", Map.of()));
                boolean improved = false;
                for (String b : baselineList) {
                    double oldScore = scoreOf(baseline, b);
                    double newScore = scoreOf(latest, b);
                    if (newScore > oldScore && nextSummaries.containsKey(b)) {
                        ((Map<String, Object>) nextSummaries.get(b)).put("avgScore", newScore);
                        improved = true;
                    }
                }
                if (improved) {
                    next.put("summaries", nextSummaries);
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValue(baselineFile, next);
                    log.info("质量基准棘轮上调（只紧不松）: {}", nextSummaries);
                }
            }
            log.info("质量跑批（P4-D）: {}", verdict);
            return verdict;
        } catch (Exception e) {
            log.warn("质量跑批执行失败: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /** 棘轮比对：各基线评分跌幅超容差记入 REGRESSIONS */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ratchetCompare(Map<String, Object> baseline, Map<String, Object> latest) {
        Map<String, Object> regressions = new LinkedHashMap<>();
        Map<String, Object> baseSummaries = (Map<String, Object>) baseline.getOrDefault("summaries", Map.of());
        Map<String, Object> newSummaries = (Map<String, Object>) latest.getOrDefault("summaries", Map.of());
        for (String b : baselines.split(",")) {
            String name = b.trim();
            if (name.isEmpty() || !baseSummaries.containsKey(name) || !newSummaries.containsKey(name)) {
                continue;
            }
            double oldScore = scoreOf(baseline, name);
            double newScore = scoreOf(latest, name);
            if (oldScore > 0 && newScore < oldScore * (1 - tolerance)) {
                regressions.put(name, Map.of(
                        "baseline", oldScore, "latest", newScore,
                        "dropPct", Math.round((oldScore - newScore) / oldScore * 1000.0) / 10.0));
            }
        }
        return regressions;
    }

    @SuppressWarnings("unchecked")
    private double scoreOf(Map<String, Object> run, String baselineName) {
        Map<String, Object> summaries = (Map<String, Object>) run.getOrDefault("summaries", Map.of());
        Map<String, Object> summary = (Map<String, Object>) summaries.get(baselineName);
        return summary != null && summary.get("avgScore") instanceof Number n ? n.doubleValue() : 0;
    }

    private Map<String, Object> summaryOf(ComparisonReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", report.getReportId());
        // 展平为 JSON 形态（与写入文件的结构一致）：BaselineSummary POJO → Map，
        // 保证 ratchetCompare 的内存读取与文件回读走同一路径
        Map<String, Object> summaries = new LinkedHashMap<>();
        if (report.getSummaries() != null) {
            report.getSummaries().forEach((name, s) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("baselineName", s.getBaselineName());
                m.put("avgScore", s.getAvgScore());
                m.put("successRate", s.getSuccessRate());
                m.put("totalScenarios", s.getTotalScenarios());
                m.put("successCount", s.getSuccessCount());
                summaries.put(name, m);
            });
        }
        out.put("summaries", summaries);
        return out;
    }

    private void writeGateState(File dir, String verdict, Map<String, Object> latest,
                                Map<String, Object> regressions) throws Exception {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("verdict", verdict.startsWith("PASS") || verdict.startsWith("no-baseline") ? "PASS" : "FAIL");
        gate.put("detail", verdict);
        gate.put("checkedAt", Instant.now().toString());
        gate.put("regressions", regressions);
        gate.put("latestReportId", latest.get("reportId"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, "gate-state.json"), gate);
    }
}
