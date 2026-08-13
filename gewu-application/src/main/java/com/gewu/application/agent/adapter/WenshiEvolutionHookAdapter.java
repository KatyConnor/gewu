package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.cognition.EvolutionHook;
import com.gewu.application.orchestration.FailureCaseService;
import com.gewu.application.wenshi.knowledge.EpisodicMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * EvolutionHook SPI 适配器 - 桥接 Wenshi 学习层到 Agent 引擎编排生命周期。
 * <p>在编排引擎的关键生命周期节点（节点完成/图完成/目标失败/目标成功）
 * 自动将执行数据写入情景记忆，为后续经验蒸馏和反思提供原始数据。
 * <p>目标失败时自动记录失败案例到 {@link FailureCaseService}，实现可控反思闭环。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiEvolutionHookAdapter implements EvolutionHook {

    private final EpisodicMemoryService episodicMemoryService;
    private final FailureCaseService failureCaseService;

    private static final String DEFAULT_TENANT = "default";
    private static final String SYSTEM_USER = "system";
    private static final int MAX_CONTENT_LENGTH = 1000;

    /**
     * 节点完成后：记录推理轨迹到情景记忆。
     */
    @Override
    public void onNodeComplete(String nodeId, String nodeResult) {
        try {
            String content = nodeId + ": " + truncate(nodeResult);
            episodicMemoryService.record(DEFAULT_TENANT, SYSTEM_USER, null, "NODE_COMPLETE", content, null);
            log.debug("EvolutionHook.onNodeComplete: nodeId={}", nodeId);
        } catch (Exception e) {
            log.debug("EvolutionHook.onNodeComplete failed: {}", e.getMessage());
        }
    }

    /**
     * 图完成后：记录编排结果与反思结论到情景记忆。
     */
    @Override
    public String onGraphComplete(String graphId, String result, String reflection) {
        try {
            String content = "graphId=" + graphId
                    + ", result=" + truncate(result)
                    + ", reflection=" + truncate(reflection);
            episodicMemoryService.record(DEFAULT_TENANT, SYSTEM_USER, null, "GRAPH_COMPLETE", content, null);
            log.debug("EvolutionHook.onGraphComplete: graphId={}", graphId);
        } catch (Exception e) {
            log.debug("EvolutionHook.onGraphComplete failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 目标失败后：记录失败经验到情景记忆 + 写入失败案例库。
     */
    @Override
    public void onGoalFailure(String goalId, String errorMessage) {
        try {
            String content = "goalId=" + goalId + ", error=" + truncate(errorMessage);
            episodicMemoryService.record(DEFAULT_TENANT, SYSTEM_USER, null, "GOAL_FAILURE", content, null);
            // 写入失败案例库
            failureCaseService.record(
                    "GOAL", content, "goal_execution",
                    errorMessage != null ? errorMessage : "未知错误",
                    "任务执行失败", "避免相同场景下重复失败",
                    null, goalId);
            log.info("EvolutionHook.onGoalFailure: goalId={}, error={}", goalId, truncate(errorMessage));
        } catch (Exception e) {
            log.debug("EvolutionHook.onGoalFailure failed: {}", e.getMessage());
        }
    }

    /**
     * 目标成功后：记录成功经验，为经验复用积累数据。
     */
    @Override
    public void onGoalSuccess(String goalId, String result) {
        try {
            String content = "goalId=" + goalId + ", result=" + truncate(result);
            episodicMemoryService.record(DEFAULT_TENANT, SYSTEM_USER, null, "GOAL_SUCCESS", content, null);
            log.debug("EvolutionHook.onGoalSuccess: goalId={}", goalId);
        } catch (Exception e) {
            log.debug("EvolutionHook.onGoalSuccess failed: {}", e.getMessage());
        }
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_CONTENT_LENGTH ? text : text.substring(0, MAX_CONTENT_LENGTH);
    }
}
