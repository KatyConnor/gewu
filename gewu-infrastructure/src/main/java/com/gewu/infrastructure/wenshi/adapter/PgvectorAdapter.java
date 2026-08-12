package com.gewu.infrastructure.wenshi.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 PostgreSQL pgvector 扩展的向量存储适配器。
 * <p>
 * 使用 wenshi_semantic_fragment 表存储向量片段，支持：
 * <ul>
 *   <li>批量 upsert（INSERT ON CONFLICT UPDATE）</li>
 *   <li>基于 <=> 算符的余弦相似度检索</li>
 *   <li>按 tenantId/source/ownerUserId 过滤</li>
 *   <li>多租户隔离（通过 SET app.current_tenant）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class PgvectorAdapter implements VectorStoreAdapter {

    /** 允许作为查询过滤条件的字段白名单，防止 SQL 注入 */
    private static final Set<String> ALLOWED_FILTER_KEYS = Set.of("tenantId", "source", "ownerUserId");

    private final DataSource wenshiDataSource;

    /**
     * {@inheritDoc}
     * <p>
     * 使用 INSERT ON CONFLICT 实现幂等写入，同一 ID 重复写入会更新内容和向量。
     * 操作在 wenshiTransactionManager 事务下执行。
     */
    @Override
    @Transactional(transactionManager = "wenshiTransactionManager", rollbackFor = Exception.class)
    public void upsert(List<VectorFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO wenshi_semantic_fragment (id, tenant_id, owner_user_id, content, source, confidence, embedding, metadata, created_at, updated_at, created_by, updated_by) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?::jsonb, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding, updated_at = EXCLUDED.updated_at";

        try (Connection conn = wenshiDataSource.getConnection()) {
            // 设置租户上下文，配合 RLS 策略实现数据隔离
            setTenantContext(conn, fragments.get(0).getMetadata().get("tenantId"));
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (VectorFragment fragment : fragments) {
                    ps.setString(1, fragment.getId());
                    ps.setString(2, (String) fragment.getMetadata().get("tenantId"));
                    ps.setString(3, (String) fragment.getMetadata().get("userId"));
                    ps.setString(4, fragment.getContent());
                    ps.setString(5, (String) fragment.getMetadata().getOrDefault("source", "MANUAL"));
                    ps.setBigDecimal(6, new java.math.BigDecimal("1.0"));
                    ps.setString(7, toVectorString(fragment.getEmbedding()));
                    ps.setString(8, (String) fragment.getMetadata().get("metadata"));
                    long now = System.currentTimeMillis();
                    ps.setLong(9, now);
                    ps.setLong(10, now);
                    ps.setString(11, (String) fragment.getMetadata().get("userId"));
                    ps.setString(12, (String) fragment.getMetadata().get("userId"));
                    ps.addBatch();
                }
                ps.executeBatch();
                log.debug("PgvectorAdapter.upsert: count={}", fragments.size());
            }
        } catch (SQLException e) {
            log.error("PgvectorAdapter.upsert failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upsert vectors", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 使用 pgvector 的 <=> 算符（余弦距离）进行 ANN 检索，返回距离最近的 topK 条记录。
     * 仅返回 id 和 content 字段，embedding 和 metadata 不加载以减少 IO。
     */
    @Override
    public List<VectorFragment> search(float[] query, int topK, Map<String, Object> filters) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, content, embedding, metadata FROM wenshi_semantic_fragment WHERE 1=1"
        );

        List<Object> params = new ArrayList<>();

        // 仅允许白名单中的过滤字段，防止 SQL 注入
        if (filters != null) {
            for (String key : ALLOWED_FILTER_KEYS) {
                if (filters.containsKey(key)) {
                    sql.append(" AND ").append(key).append(" = ?");
                    params.add(filters.get(key));
                }
            }
        }

        // <=> 为 pgvector 余弦距离算符，值越小越相似
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(toVectorString(query));
        params.add(topK);

        List<VectorFragment> results = new ArrayList<>();
        try (Connection conn = wenshiDataSource.getConnection()) {
            Object tenantId = filters != null ? filters.get("tenantId") : null;
            setTenantContext(conn, tenantId);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        VectorFragment fragment = VectorFragment.builder()
                                .id(rs.getString("id"))
                                .content(rs.getString("content"))
                                .build();
                        results.add(fragment);
                    }
                }
            }
            log.debug("PgvectorAdapter.search: results={}", results.size());
        } catch (SQLException e) {
            log.error("PgvectorAdapter.search failed: {}", e.getMessage());
        }

        return results;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 使用 ANY(?) 语法批量删除，比逐条删除性能更优。
     */
    @Override
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        String sql = "DELETE FROM wenshi_semantic_fragment WHERE id = ANY(?::text[])";
        try (Connection conn = wenshiDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", ids.toArray()));
            ps.executeUpdate();
            log.debug("PgvectorAdapter.delete: count={}", ids.size());
        } catch (SQLException e) {
            log.error("PgvectorAdapter.delete failed: {}", e.getMessage());
        }
    }

    /**
     * 设置当前连接的租户上下文变量。
     * <p>
     * 配合 PostgreSQL RLS（行级安全策略）实现多租户数据隔离。
     * 使用单引号转义防止 SQL 注入。
     *
     * @param conn     数据库连接
     * @param tenantId 租户 ID，为 null 时不设置
     */
    private void setTenantContext(Connection conn, Object tenantId) {
        if (tenantId == null) return;
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("SET app.current_tenant = '" + tenantId.toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            log.debug("PgvectorAdapter: could not set tenant context: {}", e.getMessage());
        }
    }

    /**
     * 将浮点数组转换为 pgvector 字面量格式 [0.1,0.2,...]。
     * <p>
     * 保留 6 位小数以平衡精度与存储开销。
     *
     * @param vector 浮点向量
     * @return pgvector 字符串表示，如 "[0.123456,-0.654321]"
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
}
