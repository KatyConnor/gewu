package com.gewu.application.wenshi.evaluation;

import com.gewu.infrastructure.mapper.wenshi.SemanticFragmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wenshi 复评自动触发器（AE-P1-3，45 号复评触发器自动化）。
 * <p>45 号终审裁剪 wenshi 引擎的直接原因是"知识库为空，三层认知增强无从发挥"，
 * 并预承诺复评触发条件（知识层灌入领域文档后按同题集重跑，Δ质量 ≥+5% 恢复接线）——
 * 本组件把该触发器从人工记忆变为流水线：定时巡检知识库片段行数，达到阈值即执行
 * 45 号同题集对比评测（{@link EvaluationRunner#runComparison}），并按预承诺规则自动判定。
 * <p>配置（application.yml，默认关闭——知识库灌入后开启）：
 * <pre>
 * gewu.evaluation.reeval:
 *   enabled: false                # 总开关（灌入完成后置 true）
 *   fragment-threshold: 500       # 知识库片段行数阈值
 *   baselines: baseline,wenshi    # 对比分组（ExperimentService.PRESET_GROUPS 同名）
 *   cron: "0 30 3 * * *"          # 巡检时刻
 * </pre>
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@Slf4j
@Component
public class WenshiReevaluationTrigger {

    private final ObjectProvider<SemanticFragmentMapper> semanticFragmentMapper;
    private final ObjectProvider<EvaluationRunner> evaluationRunner;

    @Value("${gewu.evaluation.reeval.enabled:false}")
    boolean enabled;
    @Value("${gewu.evaluation.reeval.fragment-threshold:500}")
    long fragmentThreshold;
    @Value("${gewu.evaluation.reeval.baselines:baseline,wenshi}")
    String baselines;

    /** 单次闸门：阈值达标后只触发一轮复评；复评后再灌入需 rearm()（运维手动或重启） */
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private volatile String lastResult = "not-run";

    public WenshiReevaluationTrigger(
            ObjectProvider<SemanticFragmentMapper> semanticFragmentMapper,
            ObjectProvider<EvaluationRunner> evaluationRunner) {
        this.semanticFragmentMapper = semanticFragmentMapper;
        this.evaluationRunner = evaluationRunner;
    }

    /** 定时巡检（默认每日 03:30，可配 cron） */
    @Scheduled(cron = "${gewu.evaluation.reeval.cron:0 30 3 * * *}")
    public void scheduledReevalCheck() {
        tryRun("scheduled");
    }

    /**
     * 执行一次复评检查（手动触发入口与定时巡检共用）。
     *
     * @return 结果一句话：disabled / below-threshold(n/threshold) / already-triggered /
     *         REEVAL_PASS(...)（建议启动 D-6 接线评估） / REEVAL_HOLD(...)（维持 legacy） / error(...)
     */
    public synchronized String tryRun(String source) {
        if (!enabled) {
            lastResult = "disabled";
            return lastResult;
        }
        SemanticFragmentMapper mapper = semanticFragmentMapper.getIfAvailable();
        EvaluationRunner runner = evaluationRunner.getIfAvailable();
        if (mapper == null || runner == null) {
            lastResult = "infrastructure-unavailable";
            return lastResult;
        }
        try {
            long count = mapper.selectCount(null);
            if (count < fragmentThreshold) {
                lastResult = "below-threshold(" + count + "/" + fragmentThreshold + ")";
                log.debug("Wenshi 复评巡检（{}）: {}", source, lastResult);
                return lastResult;
            }
            if (!armed.compareAndSet(true, false)) {
                lastResult = "already-triggered";
                return lastResult;
            }
            List<String> baselineList = Arrays.stream(baselines.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            log.info("Wenshi 复评触发（来源={}）：知识库片段 {} ≥ 阈值 {}，执行 45 号同题集对比评测: baselines={}",
                    source, count, fragmentThreshold, baselineList);
            ComparisonReport report = runner.runComparison(baselineList, List.of());
            lastResult = decide(report);
            log.info("Wenshi 复评完成（AE-P1-3）: {}", lastResult);
            return lastResult;
        } catch (Exception e) {
            // 失败重新武装：下轮巡检可重试（成本受阈值+单次闸门约束，无失控面）
            armed.set(true);
            lastResult = "error: " + e.getMessage();
            log.warn("Wenshi 复评执行失败: {}", e.getMessage());
            return lastResult;
        }
    }

    /** 45 号预承诺规则自动判定：Δ质量 ≥ +5% 建议接线评估，否则维持 legacy */
    private String decide(ComparisonReport report) {
        Map<String, ComparisonReport.BaselineSummary> summaries = report.getSummaries();
        ComparisonReport.BaselineSummary baseline = summaries.get("baseline");
        ComparisonReport.BaselineSummary wenshi = summaries.get("wenshi");
        if (baseline == null || wenshi == null) {
            return "report-incomplete: baselines=" + summaries.keySet();
        }
        double deltaPct = baseline.getAvgScore() > 0
                ? (wenshi.getAvgScore() - baseline.getAvgScore()) / baseline.getAvgScore() : 0;
        if (deltaPct >= 0.05) {
            return String.format(
                    "REEVAL_PASS: Δ质量=%+.1f%%（baseline=%.2f wenshi=%.2f）→ 按 43 号预承诺规则建议启动 D-6 接线评估（AE-P1-1 灰度）",
                    deltaPct * 100, baseline.getAvgScore(), wenshi.getAvgScore());
        }
        return String.format(
                "REEVAL_HOLD: Δ质量=%+.1f%%（baseline=%.2f wenshi=%.2f）→ 维持 legacy，继续灌入知识库后 rearm 重评",
                deltaPct * 100, baseline.getAvgScore(), wenshi.getAvgScore());
    }

    /** 最近一次巡检/复评结果（巡检接口可透出） */
    public String getLastResult() {
        return lastResult;
    }

    /** 重新武装单次闸门（复评后再灌入知识库后调用） */
    public void rearm() {
        armed.set(true);
    }
}
