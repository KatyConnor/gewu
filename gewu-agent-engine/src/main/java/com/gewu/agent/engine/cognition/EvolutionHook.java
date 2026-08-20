package com.gewu.agent.engine.cognition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 进化钩子 SPI - 嵌入编排引擎生命周期，自动触发进化闭环。
 * <p>使用方实现此接口对接进化引擎（如 Wenshi 学习层），
 * 在节点完成 / 图完成 / 目标失败时自动触发反思 / 经验抽取 / 技能演化。
 *
 * @since 1.0.0
 */
public interface EvolutionHook {

    /**
     * 节点完成后：记录推理轨迹。
     */
    void onNodeComplete(String nodeId, String nodeResult);

    /**
     * 图完成后：反思 + 经验抽取 + 质量评估 + 技能演化。
     *
     * @param graphId    图 ID
     * @param result     编排结果
     * @param reflection 反思结论（由使用方实现填充）
     * @return 提取的经验列表（JSON 字符串）
     */
    default String onGraphComplete(String graphId, String result, String reflection) {
        return null;
    }

    /**
     * 目标失败后：失败经验抽取（避免重蹈覆辙）。
     */
    default void onGoalFailure(String goalId, String errorMessage) {
    }

    /**
     * 目标成功后：成功经验沉淀。
     */
    default void onGoalSuccess(String goalId, String result) {
    }
}