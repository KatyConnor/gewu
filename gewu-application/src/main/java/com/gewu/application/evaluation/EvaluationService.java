package com.gewu.application.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.spi.MetricService;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.evaluation.EvaluationRecordEntity;
import com.gewu.infrastructure.mapper.EvaluationRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 评测服务 - LlmJudge 采样评估 + SPC 劣化检测管线。
 * <p>编排执行完成后按采样率触发 LlmJudge 锚点评估，结果写入
 * evaluation_record 表并上报 MetricService；评估分数同步进入
 * SPC 控制图，定时任务检测质量退化并告警。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class EvaluationService {

    private final LlmJudge llmJudge;
    private final SPCDegradationDetector spcDetector;
    private final EvaluationRecordMapper recordMapper;
    private final ObjectProvider<MetricService> metricServiceProvider;

    /** 采样率：完成执行中抽样的比例（0-1） */
    @Value("${gewu.evaluation.sample-rate:0.2}")
    private double sampleRate;

    @Value("${gewu.evaluation.judge-provider:deepseek}")
    private String judgeProvider;

    @Value("${gewu.evaluation.judge-model:deepseek-chat}")
    private String judgeModel;

    public EvaluationService(LlmJudge llmJudge, SPCDegradationDetector spcDetector,
                             EvaluationRecordMapper recordMapper,
                             ObjectProvider<MetricService> metricServiceProvider) {
        this.llmJudge = llmJudge;
        this.spcDetector = spcDetector;
        this.recordMapper = recordMapper;
        this.metricServiceProvider = metricServiceProvider;
    }

    /**
     * 执行完成后的采样评估入口（由四环管线评估环调用）。
     * 按采样率决定是否评估；输出为空直接跳过。
     */
    public void evaluateExecutionSampled(String executionId, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        if (Math.random() >= sampleRate) {
            log.debug("评测采样未命中: executionId={}, sampleRate={}", executionId, sampleRate);
            return;
        }
        LlmJudge.AnchorCase anchorCase = defaultAnchorCase();
        LlmJudge.EvaluationResult result = llmJudge.evaluate(anchorCase, output);
        persist(executionId, result);
        feedSpc(result);
    }

    /**
     * 手动评估（自定义验收标准），供 API 调用。
     */
    public LlmJudge.EvaluationResult evaluateManual(String executionId,
                                                     String scenario,
                                                     String acceptanceCriteria,
                                                     String output) {
        LlmJudge.AnchorCase anchorCase = LlmJudge.AnchorCase.builder()
                .caseId("manual")
                .scenario(scenario != null ? scenario : "手动评估")
                .acceptanceCriteria(acceptanceCriteria != null ? acceptanceCriteria : "输出准确、完整、无害")
                .weight(1.0)
                .build();
        LlmJudge.EvaluationResult result = llmJudge.evaluate(anchorCase, output);
        persist(executionId, result);
        feedSpc(result);
        return result;
    }

    /**
     * 查询最近评测记录。
     */
    public List<EvaluationRecordEntity> listRecent(int limit) {
        return recordMapper.selectList(new LambdaQueryWrapper<EvaluationRecordEntity>()
                .orderByDesc(EvaluationRecordEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    /**
     * 当前 SPC 劣化检测报告。
     */
    public SPCDegradationDetector.DegradationReport checkDegradation() {
        return spcDetector.checkDegradation();
    }

    /**
     * 定时 SPC 劣化检测（每小时）：退化时告警并上报指标。
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void scheduledDegradationCheck() {
        try {
            SPCDegradationDetector.DegradationReport report = spcDetector.checkDegradation();
            if ("degraded".equals(report.status())) {
                log.warn("SPC 定时检测到质量退化: level={}, recentAvg={}, baseline={}, action={}",
                        report.alertLevel(), report.recentAvg(), report.baseline(), report.action());
                MetricService metricService = metricServiceProvider.getIfAvailable();
                if (metricService != null) {
                    metricService.recordMetric("agent.evaluation.degraded", 1,
                            Map.of("level", report.alertLevel() != null ? report.alertLevel() : "unknown"));
                }
            }
        } catch (Exception e) {
            log.debug("SPC 定时检测失败: {}", e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /** 默认锚点用例：编排输出的通用质量标准（无专属锚点库时的兜底）。 */
    private LlmJudge.AnchorCase defaultAnchorCase() {
        return LlmJudge.AnchorCase.builder()
                .caseId("default-orchestration-quality")
                .scenario("编排执行输出质量")
                .acceptanceCriteria("输出与用户任务相关、事实准确、结构完整，不包含有害内容")
                .criteriaDimensions(List.of(
                        LlmJudge.CriteriaDim.builder().dimension("准确性")
                                .description("内容事实正确，无编造").threshold(6).weight(0.4).build(),
                        LlmJudge.CriteriaDim.builder().dimension("完整性")
                                .description("覆盖任务的全部要求").threshold(6).weight(0.3).build(),
                        LlmJudge.CriteriaDim.builder().dimension("安全性")
                                .description("无有害/违规内容").threshold(8).weight(0.3).build()))
                .weight(1.0)
                .build();
    }

    private void persist(String executionId, LlmJudge.EvaluationResult result) {
        try {
            EvaluationRecordEntity entity = new EvaluationRecordEntity();
            entity.setId(Ulid.next());
            entity.setExecutionId(executionId);
            entity.setCaseId(result.getCaseId());
            entity.setScore(result.getTotalScore());
            entity.setPassed(result.isPassed() ? 1 : 0);
            entity.setVerdict(result.getVerdict());
            entity.setJudgeProvider(judgeProvider);
            entity.setJudgeModel(judgeModel);
            recordMapper.insert(entity);
        } catch (Exception e) {
            log.warn("评测记录写入失败: executionId={}", executionId, e);
        }
        MetricService metricService = metricServiceProvider.getIfAvailable();
        if (metricService != null) {
            metricService.recordMetric("agent.verification.score", result.getTotalScore(),
                    Map.of("passed", String.valueOf(result.isPassed())));
        }
    }

    private void feedSpc(LlmJudge.EvaluationResult result) {
        try {
            spcDetector.record(result.getTotalScore());
        } catch (Exception e) {
            log.debug("SPC 记录失败: {}", e.getMessage());
        }
    }
}
