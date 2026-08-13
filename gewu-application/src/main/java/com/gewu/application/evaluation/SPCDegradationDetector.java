package com.gewu.application.evaluation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SPC 退化检测器 - 使用控制图监控评估分数，区分正常波动与真实退化。
 * <p>退化判定规则：
 * <ul>
 *   <li>近期平均分低于下控限（LCL = baseline - 2σ）→ 严重退化，自动回滚</li>
 *   <li>连续 7 次下降趋势 → 轻度退化，增加评估频率</li>
 *   <li>近 5 次中 ≥3 次低于 baseline - 1σ → 轻度退化，告警检查</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class SPCDegradationDetector {

    private static final int WINDOW_SIZE = 50;
    private static final int MIN_DATA_POINTS = 10;
    private static final double CONTROL_LIMIT_SIGMA = 2.0;

    private final Queue<Double> scoreHistory = new ConcurrentLinkedQueue<>();

    /**
     * 记录评估分数。
     */
    public void record(double score) {
        scoreHistory.offer(score);
        while (scoreHistory.size() > WINDOW_SIZE) {
            scoreHistory.poll();
        }
    }

    /**
     * 检测退化。
     */
    public DegradationReport checkDegradation() {
        if (scoreHistory.size() < MIN_DATA_POINTS) {
            return DegradationReport.insufficient();
        }

        List<Double> scores = new ArrayList<>(scoreHistory);
        int n = scores.size();
        int recentN = Math.min(5, n / 2);

        double baseline = mean(scores.subList(0, n - recentN));
        double std = stdDev(scores.subList(0, n - recentN));
        double recentAvg = mean(scores.subList(n - recentN, n));

        double ucl = baseline + CONTROL_LIMIT_SIGMA * std;
        double lcl = baseline - CONTROL_LIMIT_SIGMA * std;

        if (recentAvg < lcl) {
            log.warn("SPC 检测到严重退化: recentAvg={}, lcl={}, baseline={}", recentAvg, lcl, baseline);
            return DegradationReport.degraded("critical", ucl, lcl, recentAvg, baseline, "auto_rollback");
        }

        if (consecutiveDownward(scores, 7)) {
            log.warn("SPC 检测到连续下降趋势");
            return DegradationReport.degraded("warning", ucl, lcl, recentAvg, baseline, "increase_eval_frequency");
        }

        long belowOneSigma = scores.subList(n - recentN, n).stream()
                .filter(s -> s < baseline - std)
                .count();
        if (belowOneSigma >= 3) {
            log.warn("SPC 检测到多数低于1σ: count={}", belowOneSigma);
            return DegradationReport.degraded("warning", ucl, lcl, recentAvg, baseline, "alert_and_inspect");
        }

        return DegradationReport.normal(ucl, lcl, recentAvg, baseline);
    }

    private boolean consecutiveDownward(List<Double> scores, int n) {
        if (scores.size() < n + 1) return false;
        int count = 0;
        for (int i = scores.size() - n; i < scores.size(); i++) {
            if (scores.get(i) < scores.get(i - 1)) count++;
        }
        return count >= n - 1;
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double stdDev(List<Double> values) {
        double mean = mean(values);
        return Math.sqrt(values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0));
    }

    // ===== DTO =====

    public record DegradationReport(String status, String alertLevel, Double ucl, Double lcl,
                                     Double recentAvg, Double baseline, String action) {
        public static DegradationReport insufficient() {
            return new DegradationReport("insufficient_data", null, null, null, null, null, null);
        }

        public static DegradationReport normal(double ucl, double lcl, double recentAvg, double baseline) {
            return new DegradationReport("normal", null, ucl, lcl, recentAvg, baseline, null);
        }

        public static DegradationReport degraded(String level, double ucl, double lcl,
                                                  double recentAvg, double baseline, String action) {
            return new DegradationReport("degraded", level, ucl, lcl, recentAvg, baseline, action);
        }
    }
}