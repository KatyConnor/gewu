package com.gewu.application.wenshi.reasoning;

import com.gewu.domain.wenshi.learning.Experience;
import com.gewu.infrastructure.mapper.wenshi.ExperienceMapper;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略路由器 - 为每个子目标选择最优求解策略。
 * <p>
 * 采用多级路由策略，按优先级依次尝试：
 * <ol>
 *   <li>经验复用：若存在相似任务经验则直接复用结果</li>
 *   <li>知识查找：若子目标标注为知识检索类型则路由到知识库</li>
 *   <li>规则匹配：根据子目标策略字段匹配工具执行</li>
 *   <li>LLM 兜底：以上均不匹配时使用 LLM 推理</li>
 * </ol>
 * 通过经验优先策略降低 LLM 调用频次，优化成本和延迟。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SolverRouter {

    /** 经验复用的最低质量分阈值 */
    private static final BigDecimal MIN_REUSE_SCORE = new BigDecimal("0.70");

    private final ExperienceMapper experienceMapper;
    private final EmbeddingAdapter embeddingAdapter;

    /**
     * 求解策略枚举。
     * <p>
     * 定义了四种求解方式，按成本从低到高排列。
     *
     * @since 1.0.0
     */
    public enum Strategy {
        /** 经验复用：直接使用历史推理结果，成本最低 */
        EXPERIENCE_REUSE,

        /** 知识查找：从知识库检索答案 */
        KNOWLEDGE_LOOKUP,

        /** 网络搜索：从搜索引擎获取实时信息，经正确性判断后注入推理上下文 */
        WEB_SEARCH,

        /** 工具执行：调用外部工具完成操作 */
        TOOL_EXECUTION,

        /** 文件输出：生成代码/文档/报告等复杂内容，保存为文件，对话框输出摘要 */
        FILE_OUTPUT,

        /** LLM 推理：使用大模型生成答案，成本最高 */
        LLM_REASONING
    }

    /**
     * 为子目标选择最优求解策略。
     * <p>
     * 按优先级依次尝试各路由策略，返回第一个匹配成功的策略。
     * 若启用了经验复用且约束条件允许，优先尝试经验复用。
     *
     * @param subgoal 待求解的子目标节点；不可为 null
     * @param request 推理请求，包含约束条件配置
     * @return 选定的求解策略，不会返回 null（最低保障为 LLM_REASONING）
     * @since 1.0.0
     */
    public Strategy selectStrategy(WenshiReasoningResult.SubgoalNode subgoal, WenshiReasoningRequest request) {
        // 第一优先级：经验复用（若约束条件允许）
        if (request.getConstraints() == null || request.getConstraints().isEnableExperience()) {
            Strategy experienceStrategy = tryExperienceReuse(subgoal, request);
            if (experienceStrategy != null) {
                return experienceStrategy;
            }
        }

        // 第二优先级：知识查找
        Strategy knowledgeStrategy = tryKnowledgeLookup(subgoal);
        if (knowledgeStrategy != null) {
            return knowledgeStrategy;
        }

        // 第三优先级：网络搜索（若约束条件允许且子目标标注为 WEB_SEARCH）
        if (request.getConstraints() == null || request.getConstraints().isEnableWebSearch()) {
            Strategy webStrategy = tryWebSearch(subgoal);
            if (webStrategy != null) {
                return webStrategy;
            }
        }

        // 第四优先级：规则匹配（工具执行）
        Strategy ruleStrategy = tryRuleMatch(subgoal);
        if (ruleStrategy != null) {
            return ruleStrategy;
        }

        // 第五优先级：文件输出
        Strategy fileStrategy = tryFileOutput(subgoal);
        if (fileStrategy != null) {
            return fileStrategy;
        }

        // 兜底策略：LLM 推理
        return Strategy.LLM_REASONING;
    }

    /**
     * 尝试经验复用路由。
     * <p>
     * 将子目标描述转为向量，查询经验库中最相似的经验。
     * 若命中且经验质量分达到阈值（{@value #MIN_REUSE_SCORE}），返回 EXPERIENCE_REUSE。
     *
     * @param subgoal 待求解的子目标
     * @param request 推理请求，包含用户和租户信息
     * @return 若命中经验则返回 EXPERIENCE_REUSE，否则返回 null
     * @since 1.0.0
     */
    private Strategy tryExperienceReuse(WenshiReasoningResult.SubgoalNode subgoal, WenshiReasoningRequest request) {
        String tenantId = request.getTenantId();
        if (tenantId == null) {
            return null;
        }
        try {
            float[] queryVector = embeddingAdapter.embed(subgoal.getDescription());
            String vectorStr = toVectorString(queryVector);
            List<Experience> matches = experienceMapper.searchByVector(vectorStr, tenantId, 1);
            if (!matches.isEmpty()) {
                Experience best = matches.get(0);
                if (best.getScore() != null && best.getScore().compareTo(MIN_REUSE_SCORE) >= 0) {
                    log.debug("SolverRouter.tryExperienceReuse: hit, scenario={}, score={}",
                            best.getScenario(), best.getScore());
                    return Strategy.EXPERIENCE_REUSE;
                }
            }
        } catch (Exception e) {
            log.debug("SolverRouter.tryExperienceReuse: query failed, falling through: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将浮点数组转换为 pgvector 字面量格式 [0.1,0.2,...]。
     */
    private String toVectorString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 尝试知识查找路由。
     * <p>
     * 当子目标的策略字段为 KNOWLEDGE_LOOKUP 时匹配成功。
     *
     * @param subgoal 待求解的子目标
     * @return 若匹配则返回 KNOWLEDGE_LOOKUP，否则返回 null
     * @since 1.0.0
     */
    private Strategy tryKnowledgeLookup(WenshiReasoningResult.SubgoalNode subgoal) {
        String strategy = subgoal.getStrategy();
        if ("KNOWLEDGE_LOOKUP".equals(strategy)) {
            return Strategy.KNOWLEDGE_LOOKUP;
        }
        return null;
    }

    /**
     * 尝试网络搜索路由。
     * <p>
     * 当子目标的策略字段为 WEB_SEARCH 时匹配成功。
     * 适用于时效性问题、事实性查询等需要实时网络信息的场景。
     *
     * @param subgoal 待求解的子目标
     * @return 若匹配则返回 WEB_SEARCH，否则返回 null
     * @since 1.0.0
     */
    private Strategy tryWebSearch(WenshiReasoningResult.SubgoalNode subgoal) {
        String strategy = subgoal.getStrategy();
        if ("WEB_SEARCH".equals(strategy)) {
            return Strategy.WEB_SEARCH;
        }
        return null;
    }

    /**
     * 尝试文件输出路由。
     * <p>
     * 当子目标的策略字段为 FILE_OUTPUT 时匹配成功。
     * 适用于代码生成、文档生成、报告输出等复杂内容场景。
     *
     * @param subgoal 待求解的子目标
     * @return 若匹配则返回 FILE_OUTPUT，否则返回 null
     * @since 1.0.0
     */
    private Strategy tryFileOutput(WenshiReasoningResult.SubgoalNode subgoal) {
        String strategy = subgoal.getStrategy();
        if ("FILE_OUTPUT".equals(strategy)) {
            return Strategy.FILE_OUTPUT;
        }
        return null;
    }

    /**
     * 尝试规则匹配路由。
     * <p>
     * 当子目标的策略字段为 TOOL_EXECUTION 时匹配成功。
     *
     * @param subgoal 待求解的子目标
     * @return 若匹配则返回 TOOL_EXECUTION，否则返回 null
     * @since 1.0.0
     */
    private Strategy tryRuleMatch(WenshiReasoningResult.SubgoalNode subgoal) {
        String strategy = subgoal.getStrategy();
        if ("TOOL_EXECUTION".equals(strategy)) {
            return Strategy.TOOL_EXECUTION;
        }
        return null;
    }
}
