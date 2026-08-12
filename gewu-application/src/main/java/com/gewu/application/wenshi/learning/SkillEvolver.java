package com.gewu.application.wenshi.learning;

import com.gewu.domain.wenshi.knowledge.ProceduralMemory;
import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.mapper.wenshi.ProceduralMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 技能演化器 — 将多条高质量经验演化为程序化记忆（技能）。
 * <p>
 * 演化条件：
 * <ul>
 *   <li>经验数量 &ge; {@value #MIN_EXPERIENCES} 条</li>
 *   <li>平均得分 &ge; {@value #MIN_SUCCESS_RATE}</li>
 * </ul>
 * 满足条件时，将经验集合提炼为一条可复用的技能并存入程序化记忆库。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillEvolver {

    private final ExperienceMapper experienceMapper;
    private final ProceduralMemoryMapper proceduralMemoryMapper;

    /** 演化所需最少经验条数 */
    private static final int MIN_EXPERIENCES = 3;
    /** 演化所需最低平均得分 */
    private static final BigDecimal MIN_SUCCESS_RATE = BigDecimal.valueOf(0.85);

    /**
     * 将经验列表演化为程序化记忆（技能）。
     * <p>
     * 先校验经验数量和平均得分是否满足演化门槛，不满足则返回 null。
     *
     * @param experiences 待演化的经验列表
     * @return 演化成功的技能对象，不满足条件时返回 null
     * @since 1.0.0
     */
    public ProceduralMemory evolve(List<Experience> experiences) {
        if (experiences == null || experiences.size() < MIN_EXPERIENCES) {
            return null;
        }

        BigDecimal avgScore = calculateAverageScore(experiences);
        if (avgScore.compareTo(MIN_SUCCESS_RATE) < 0) {
            // 平均得分不达标说明经验质量不够，暂不演化
            return null;
        }

        ProceduralMemory skill = new ProceduralMemory();
        skill.setId(UUID.randomUUID().toString());
        skill.setTenantId(experiences.get(0).getTenantId());
        skill.setType("SKILL");
        skill.setName(generateSkillName(experiences));
        skill.setDescription(generateDescription(experiences));
        skill.setDefinition(generateDefinition(experiences));
        skill.setSkillLevel(calculateSkillLevel(avgScore));
        skill.setStatus(1);

        proceduralMemoryMapper.insert(skill);
        log.info("SkillEvolver.evolve: skillName={}, level={}", skill.getName(), skill.getSkillLevel());
        return skill;
    }

    /**
     * 计算经验列表的平均得分。
     *
     * @param experiences 经验列表
     * @return 平均得分，保留 2 位小数
     * @since 1.0.0
     */
    private BigDecimal calculateAverageScore(List<Experience> experiences) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Experience exp : experiences) {
            sum = sum.add(exp.getScore() != null ? exp.getScore() : BigDecimal.ZERO);
        }
        return sum.divide(BigDecimal.valueOf(experiences.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 根据经验生成技能名称。
     *
     * @param experiences 经验列表
     * @return 技能名称
     * @since 1.0.0
     */
    private String generateSkillName(List<Experience> experiences) {
        return "Skill_" + experiences.get(0).getScenarioHash();
    }

    /**
     * 生成技能描述。
     *
     * @param experiences 经验列表
     * @return 技能描述文本
     * @since 1.0.0
     */
    private String generateDescription(List<Experience> experiences) {
        return "自动演化的技能，基于 " + experiences.size() + " 条经验";
    }

    /**
     * 生成技能定义（JSON 格式）。
     * <p>
     * 将每条经验的策略步骤序列化为 steps 数组，供后续执行时参考。
     *
     * @param experiences 经验列表
     * @return JSON 格式的技能定义，序列化失败时返回空 steps
     * @since 1.0.0
     */
    private String generateDefinition(List<Experience> experiences) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode steps = mapper.createArrayNode();
            for (int i = 0; i < experiences.size(); i++) {
                com.fasterxml.jackson.databind.node.ObjectNode step = mapper.createObjectNode();
                step.put("step", i + 1);
                String strategy = experiences.get(i).getStrategy();
                // 截断过长策略文本，控制定义体积
                step.put("action", strategy != null ? strategy.substring(0, Math.min(200, strategy.length())) : "");
                steps.add(step);
            }
            com.fasterxml.jackson.databind.node.ObjectNode definition = mapper.createObjectNode();
            definition.set("steps", steps);
            return mapper.writeValueAsString(definition);
        } catch (Exception e) {
            // 序列化异常时返回空定义，不影响主流程
            return "{\"steps\":[]}";
        }
    }

    /**
     * 根据平均得分计算技能等级。
     * <p>
     * 等级划分：&ge;0.95→5级, &ge;0.90→4级, &ge;0.85→3级, &ge;0.80→2级, 其他→1级。
     *
     * @param avgScore 平均得分
     * @return 技能等级（1~5）
     * @since 1.0.0
     */
    private int calculateSkillLevel(BigDecimal avgScore) {
        if (avgScore.compareTo(BigDecimal.valueOf(0.95)) >= 0) return 5;
        if (avgScore.compareTo(BigDecimal.valueOf(0.90)) >= 0) return 4;
        if (avgScore.compareTo(BigDecimal.valueOf(0.85)) >= 0) return 3;
        if (avgScore.compareTo(BigDecimal.valueOf(0.80)) >= 0) return 2;
        return 1;
    }
}
