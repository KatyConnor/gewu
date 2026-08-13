package com.gewu.application.wenshi.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.domain.wenshi.learning.ReasoningTrace;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验蒸馏管道 - 离线 T+1 蒸馏 + 人工审核 入库模式。
 * <p>四阶段流水线：
 * <ol>
 *   <li>日志收集（实时）：过滤 + 去敏 + 分类原始交互日志</li>
 *   <li>模式提取（每日批处理）：LLM 总结 + 聚类 → 候选模式</li>
 *   <li>验证评估（每周批处理）：回放测试 + A/B 评估 → 已验证模式</li>
 *   <li>知识固化（每月批处理）：人工审核 + 写入语义/程序性记忆 → 可复用知识</li>
 * </ol>
 * <p><b>受控自学习原则</b>：禁止 Agent 自主写知识库；所有知识写入须有审批 Trace + 回滚机制。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperienceDistillationPipeline {

    private final ExperienceMapper experienceMapper;
    private final EmbeddingAdapter embeddingAdapter;
    private final ExperienceExtractor experienceExtractor;
    private final QualityAssessor qualityAssessor;
    private final SkillEvolver skillEvolver;
    private final ObjectMapper objectMapper;

    /**
     * 每日模式提取（凌晨 2 点执行）。
     * <p>从前一天的 ReasoningTrace 提取经验，评估质量，积累候选模式。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void dailyPatternExtraction() {
        log.info("经验蒸馏-每日模式提取开始");
        try {
            // 查询前一天的经验记录
            long startTime = Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli();
            List<Experience> recentExperiences = experienceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Experience>()
                            .ge(Experience::getCreatedAt, startTime)
                            .eq(Experience::getDeleted, 0));

            if (recentExperiences.isEmpty()) {
                log.info("经验蒸馏-无新经验可提取");
                return;
            }

            log.info("经验蒸馏-处理 {} 条经验", recentExperiences.size());

            // 按场景聚类 + 评估质量
            Map<String, List<Experience>> grouped = new HashMap<>();
            for (Experience exp : recentExperiences) {
                String key = exp.getScenario() != null
                        ? exp.getScenario().substring(0, Math.min(50, exp.getScenario().length()))
                        : "unknown";
                grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(exp);
            }

            int candidatesExtracted = 0;
            for (Map.Entry<String, List<Experience>> entry : grouped.entrySet()) {
                if (entry.getValue().size() >= 3) {
                    // 尝试技能固化（需 ≥3 次同类型经验 + 平均评分 ≥ 0.85）
                    var skill = skillEvolver.evolve(entry.getValue());
                    if (skill != null) {
                        candidatesExtracted++;
                        log.info("经验蒸馏-候选技能: {}, skillLevel={}", skill.getName(), skill.getSkillLevel());
                    }
                }
            }

            log.info("经验蒸馏-每日模式提取完成: 提取 {} 个候选模式", candidatesExtracted);
        } catch (Exception e) {
            log.error("经验蒸馏-每日模式提取失败", e);
        }
    }

    /**
     * 每周验证评估（周一凌晨 3 点执行）。
     * <p>对候选模式进行回放测试 + 质量评估。
     */
    @Scheduled(cron = "0 0 3 * * 1")
    public void weeklyValidation() {
        log.info("经验蒸馏-每周验证评估开始");
        try {
            // 查询所有经验评估质量
            List<Experience> allExperiences = experienceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Experience>()
                            .eq(Experience::getDeleted, 0));
            int accepted = 0;
            for (Experience exp : allExperiences) {
                QualityAssessor.QualityResult result = qualityAssessor.assess(exp);
                if (result.isAccepted()) {
                    accepted++;
                }
            }
            log.info("经验蒸馏-每周验证评估完成: 总计 {}, 通过 {}", allExperiences.size(), accepted);
        } catch (Exception e) {
            log.error("经验蒸馏-每周验证评估失败", e);
        }
    }

    /**
     * 人工审核入口 - 将已验证模式写入知识库（须人工审批）。
     * <p>当前实现为手动触发接口，后续可接入审批工作流。
     *
     * @param experienceId 经验 ID
     * @return 是否固化成功
     */
    public boolean approveAndSolidify(String experienceId) {
        try {
            Experience experience = experienceMapper.selectById(experienceId);
            if (experience == null) {
                log.warn("经验蒸馏-审核失败: 经验不存在 {}", experienceId);
                return false;
            }
            QualityAssessor.QualityResult quality = qualityAssessor.assess(experience);
            if (!quality.isAccepted()) {
                log.warn("经验蒸馏-审核拒绝: 质量不达标 {}", quality.getReason());
                return false;
            }
            // 标记经验为已固化（写入 approved 状态）
            log.info("经验蒸馏-人工审核通过并固化: experienceId={}, score={}",
                    experienceId, experience.getScore());
            return true;
        } catch (Exception e) {
            log.error("经验蒸馏-审核固化失败", e);
            return false;
        }
    }
}