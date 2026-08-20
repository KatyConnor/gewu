package com.gewu.agent.engine.spi;

import java.util.Map;

/**
 * 策略服务 SPI - Agent 行为策略的查询与校验。
 * <p>共享知识层的策略组件，支持策略灰度发布与回滚。
 *
 * @since 1.0.0
 */
public interface PolicyService {

    /**
     * 获取场景的活跃策略。
     */
    default Map<String, Object> getActivePolicy(String scenario) {
        return Map.of();
    }

    /**
     * 校验操作是否符合策略。
     *
     * @return true 表示操作允许
     */
    default boolean checkPolicy(String scenario, String action) {
        return true;
    }

    /**
     * 回滚场景策略到上一版本。
     *
     * @return true 表示回滚成功（无历史版本或失败返回 false）
     */
    default boolean rollbackPolicy(String scenario) {
        return false;
    }
}