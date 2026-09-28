package com.veloflow.engine.ai;

import java.util.List;
import java.util.Map;

/**
 * 数据源桥 SPI（51 号 §2.6 database 节点，P6 二期）：只读报表查询。
 * <p>安全红线：引擎 Handler 做 SQL 形状守卫（仅 SELECT/WITH、禁多语句与注释、
 * 强制行数帽）；宿主实现负责受控数据源连接（只读连接 + 查询超时）。
 * 引擎零宿主依赖；未接入时 database 节点以可读错误完成。
 *
 * @since 1.0.0
 */
public interface FlowDataSourceBridge {

    /**
     * 只读查询。
     *
     * @param dataSourceId 数据源标识（宿主映射到受控只读连接）
     * @param sql          引擎守卫后的 SELECT 语句
     * @param maxRows      行数上限
     */
    QueryResult query(String dataSourceId, String sql, int maxRows);

    record QueryResult(List<Map<String, Object>> rows, int rowCount, long durationMs) {
    }
}
