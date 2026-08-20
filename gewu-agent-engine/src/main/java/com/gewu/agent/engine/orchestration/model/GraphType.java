package com.gewu.agent.engine.orchestration.model;

/**
 * 编排图类型。
 *
 * @since 1.0.0
 */
public enum GraphType {
    /** SDLC 流水线 */
    SDLC_PIPELINE,
    /** 自主目标分解图 */
    GOAL_DECOMPOSED,
    /** 临时图 */
    AD_HOC,
    /** 可复用模板 */
    TEMPLATE
}
