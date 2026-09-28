package com.gewu.application.config;

import com.gewu.application.wenshi.config.WenshiDataSourceConfig;
import com.veloflow.engine.ai.FlowDataSourceBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Veloflow 数据源桥（51 号 §2.6，P6 二期）：database 节点的只读查询宿主实现——
 * 绑定问石 PG 报表数据源（wenshiDataSourceHolder，与主业务库物理隔离）。
 * 安全：连接置只读 + 查询超时 + 行数上限截断；SQL 形状守卫在引擎 Handler 侧已完成。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gewu.wenshi", name = "enabled", havingValue = "true")
public class VeloflowDataSourceBridge implements FlowDataSourceBridge {

    private final ObjectProvider<WenshiDataSourceConfig.WenshiDataSourceHolder> holderProvider;

    public VeloflowDataSourceBridge(
            ObjectProvider<WenshiDataSourceConfig.WenshiDataSourceHolder> holderProvider) {
        this.holderProvider = holderProvider;
    }

    @Override
    public QueryResult query(String dataSourceId, String sql, int maxRows) {
        WenshiDataSourceConfig.WenshiDataSourceHolder holder = holderProvider.getIfAvailable();
        if (holder == null || holder.getDataSource() == null) {
            throw new IllegalStateException("问石数据源不可用（gewu.wenshi.enabled=false）");
        }
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = holder.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setReadOnly(true);
            stmt.setQueryTimeout(30);
            stmt.setMaxRows(maxRows);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询执行失败: " + e.getMessage(), e);
        }
        log.info("工作流 database 节点查询: dataSourceId={}, rows={}, durationMs={}",
                dataSourceId, rows.size(), System.currentTimeMillis() - start);
        return new QueryResult(rows, rows.size(), System.currentTimeMillis() - start);
    }
}
