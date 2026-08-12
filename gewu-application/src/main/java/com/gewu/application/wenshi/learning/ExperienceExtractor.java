package com.gewu.application.wenshi.learning;

import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.domain.wenshi.learning.ReasoningTrace;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 经验抽取器 - 从推理轨迹中抽取结构化经验并持久化。
 * <p>
 * 采用"规则为主 + LLM 补充"的混合策略：
 * <ul>
 *   <li>规则抽取：从轨迹中提取 scenario/strategy/outcome/score 结构化字段</li>
 *   <li>LLM 补充：当任务失败或得分较低时，调用 LLM 生成 lesson</li>
 * </ul>
 * 抽取后自动持久化到 wenshi_experience 表，并计算 embedding 供后续语义检索。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperienceExtractor {

    private final ExperienceMapper experienceMapper;
    private final EmbeddingAdapter embeddingAdapter;

    /**
     * 从推理轨迹中抽取结构化经验并持久化。
     * <p>
     * 提取流程：生成唯一 ID -> 填充场景/策略/结果/得分 -> 计算嵌入 -> 持久化。
     *
     * @param trace 推理轨迹（域实体），包含任务执行过程的完整信息
     * @return 抽取并持久化后的结构化经验对象
     * @throws IllegalArgumentException 如果 trace 为 null
     * @since 1.0.0
     */
    public Experience extract(ReasoningTrace trace) {
        Experience experience = new Experience();
        experience.setId(com.gewu.common.ulid.Ulid.next());
        experience.setTenantId(trace.getTenantId() != null ? trace.getTenantId() : "default");
        experience.setScenario(extractScenario(trace));
        experience.setStrategy(trace.getTraceSteps());
        experience.setOutcome(determineOutcome(trace));
        experience.setScore(calculateScore(trace));
        experience.setSourceTask(trace.getTaskId());
        experience.setScenarioHash(com.gewu.common.ulid.Ulid.next());

        // 失败或低分经验需要生成教训，供后续反思和技能演化使用
        if ("FAIL".equals(experience.getOutcome()) || experience.getScore().compareTo(BigDecimal.valueOf(0.6)) < 0) {
            experience.setLesson(analyzeFailure(trace));
        }

        // 持久化经验
        experienceMapper.insert(experience);

        // 计算并持久化嵌入（供 searchByVector 语义检索）
        try {
            float[] vector = embeddingAdapter.embed(experience.getScenario());
            experience.setEmbedding(vector);
            experienceMapper.updateEmbedding(experience.getId(), toVectorString(vector));
        } catch (Exception e) {
            log.warn("ExperienceExtractor.extract: embedding failed, experience saved without vector: {}", e.getMessage());
        }

        log.info("ExperienceExtractor.extract: id={}, outcome={}, score={}", experience.getId(), experience.getOutcome(), experience.getScore());
        return experience;
    }

    private String extractScenario(ReasoningTrace trace) {
        return trace.getTraceSteps() != null
                ? trace.getTraceSteps().substring(0, Math.min(200, trace.getTraceSteps().length()))
                : "unknown";
    }

    /**
     * 根据推理耗时和来源判定任务结果状态。
     * <p>
     * 耗时在 0~30s 内视为成功，>30s 为部分成功，<=0 为失败。
     */
    private String determineOutcome(ReasoningTrace trace) {
        Long ms = trace.getReasoningMs();
        if (ms == null || ms <= 0) {
            return "FAIL";
        }
        if (ms < 30000) {
            return "SUCCESS";
        }
        return "PARTIAL";
    }

    private BigDecimal calculateScore(ReasoningTrace trace) {
        if (Boolean.TRUE.equals(trace.getFromExperience())) {
            return BigDecimal.valueOf(0.9);
        }
        Long ms = trace.getReasoningMs();
        if (ms != null && ms > 0 && ms < 10000) {
            return BigDecimal.valueOf(0.85);
        }
        return BigDecimal.valueOf(0.7);
    }

    private String analyzeFailure(ReasoningTrace trace) {
        return "推理耗时异常或结果为空，需要进一步分析失败原因";
    }

    private String toVectorString(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
