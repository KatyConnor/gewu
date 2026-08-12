package com.gewu.application.wenshi.knowledge;

import com.gewu.application.wenshi.search.WebSearchFragment;
import com.gewu.domain.wenshi.knowledge.EpisodicEvent;
import com.gewu.domain.wenshi.knowledge.ProceduralMemory;
import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 记忆路由器 — 根据任务类型将查询分发到不同类型的记忆服务，聚合结果。
 * <p>
 * 路由策略基于 {@code taskType} 决定从哪些记忆源获取数据：
 * <ul>
 *   <li>DATA_QUERY — 语义记忆 + 工具记忆</li>
 *   <li>DATA_ANALYSIS — 语义记忆 + 情景记忆 + SOP 记忆</li>
 *   <li>PROCESS_EXECUTION — SOP 记忆 + 参数记忆</li>
 *   <li>KNOWLEDGE_QA — 仅语义记忆</li>
 *   <li>DEFAULT — 语义记忆 + 工具记忆（兜底）</li>
 * </ul>
 * 每种任务类型对应不同的记忆组合，确保 LLM 获取最相关的上下文。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryRouter {

    private final SemanticMemoryService semanticMemoryService;
    private final EpisodicMemoryService episodicMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final ParametricMemoryService parametricMemoryService;

    /**
     * 根据任务类型路由记忆查询，返回聚合的路由结果。
     * <p>
     * 不同任务类型会触发不同的记忆源组合查询，结果封装在 {@link RoutingResult} 中。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param taskType 任务类型（DATA_QUERY / DATA_ANALYSIS / PROCESS_EXECUTION / KNOWLEDGE_QA）
     * @param query    查询文本，用于语义检索
     * @return 路由结果，包含各类型记忆列表和路由决策枚举
     * @since 1.0.0
     */
    public RoutingResult route(String tenantId, String userId, String taskType, String query) {
        RoutingResult.RoutingResultBuilder result = RoutingResult.builder();
        result.tenantId(tenantId);
        result.userId(userId);
        result.taskType(taskType);

        switch (taskType) {
            case "DATA_QUERY":
                // 数据查询场景：需要业务知识 + 可用工具
                result.semanticMemories(semanticMemoryService.search(tenantId, query, 5));
                result.proceduralMemories(proceduralMemoryService.findByType(tenantId, "TOOL"));
                result.decision(RoutingDecision.DATA_QUERY);
                break;
            case "DATA_ANALYSIS":
                // 数据分析场景：需要业务知识 + 用户历史行为 + 分析 SOP
                result.semanticMemories(semanticMemoryService.search(tenantId, query, 5));
                result.episodicMemories(episodicMemoryService.queryByUser(tenantId, userId, 10));
                result.proceduralMemories(proceduralMemoryService.findByType(tenantId, "SOP"));
                result.decision(RoutingDecision.DATA_ANALYSIS);
                break;
            case "PROCESS_EXECUTION":
                // 流程执行场景：需要 SOP + 用户偏好参数
                result.proceduralMemories(proceduralMemoryService.findByType(tenantId, "SOP"));
                result.parametricMemory(parametricMemoryService.getProfile(tenantId, userId));
                result.decision(RoutingDecision.PROCESS_EXECUTION);
                break;
            case "KNOWLEDGE_QA":
                // 知识问答场景：仅需语义记忆
                result.semanticMemories(semanticMemoryService.search(tenantId, query, 5));
                result.decision(RoutingDecision.KNOWLEDGE_QA);
                break;
            default:
                // 兜底策略：少量语义记忆 + 工具记忆
                result.semanticMemories(semanticMemoryService.search(tenantId, query, 3));
                result.proceduralMemories(proceduralMemoryService.findByType(tenantId, "TOOL"));
                result.decision(RoutingDecision.DEFAULT);
                break;
        }

        RoutingResult built = result.build();
        log.debug("MemoryRouter.route: taskType={}, semantic={}, episodic={}, procedural={}",
                taskType,
                built.getSemanticMemories() != null ? built.getSemanticMemories().size() : 0,
                built.getEpisodicMemories() != null ? built.getEpisodicMemories().size() : 0,
                built.getProceduralMemories() != null ? built.getProceduralMemories().size() : 0);

        return built;
    }

    /**
     * 记忆路由结果 — 封装各类型记忆数据及路由决策。
     * <p>
     * 由 {@link MemoryRouter#route} 构建，传递给 {@link MemoryInjector} 进行 Prompt 注入。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    public static class RoutingResult {
        /** 租户 ID。 */
        private String tenantId;
        /** 用户 ID。 */
        private String userId;
        /** 触发路由的任务类型。 */
        private String taskType;
        /** 语义记忆片段列表。 */
        private List<SemanticFragment> semanticMemories;
        /** 情景记忆事件列表。 */
        private List<EpisodicEvent> episodicMemories;
        /** 程序记忆列表（工具/SOP/技能）。 */
        private List<ProceduralMemory> proceduralMemories;
        /** 参数记忆（用户偏好键值对）。 */
        private Map<String, String> parametricMemory;
        /** 网络搜索结果条目列表（经正确性判断后注入，由 WebSearchService 产出）。 */
        private List<WebSearchFragment> webSearchResults;
        /** 路由决策枚举，标识最终匹配的策略。 */
        private RoutingDecision decision;
    }

    /**
     * 路由决策枚举 — 标识记忆路由匹配的策略类型。
     *
     * @since 1.0.0
     */
    public enum RoutingDecision {
        /** 数据查询策略。 */
        DATA_QUERY,
        /** 数据分析策略。 */
        DATA_ANALYSIS,
        /** 流程执行策略。 */
        PROCESS_EXECUTION,
        /** 知识问答策略。 */
        KNOWLEDGE_QA,
        /** 默认兜底策略。 */
        DEFAULT
    }
}
