package com.gewu.desktop.adapter;

import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话/消息存储（D1-9）：local_session / local_message 的 CRUD。
 * 表结构对齐平台 session/session_message 语义（含 S9 五列与 client_id 幂等，ADR-006），
 * 协同同步层按字段搬运即可。status 口径：0 进行中 / 2 已归档 / 3 已删除。
 *
 * @since 1.0.0
 */
@Component
public class DesktopSessionStore {

    private final JdbcTemplate jdbc;

    public DesktopSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 会话 ====================

    public Map<String, Object> createSession(String title, String agentId, String modelProvider,
                                             String modelName, String projectId, String directory,
                                             String workspacePath) {
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        jdbc.update("""
                        INSERT INTO local_session (id, title, agent_id, runtime, model_provider, model_name,
                            project_id, directory, workspace_path, status, pinned, message_count, created_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,0,0,0,?,?)""",
                id, title == null || title.isBlank() ? "新对话" : title,
                agentId, "local", modelProvider, modelName,
                projectId, directory, workspacePath, now, now);
        return getSession(id);
    }

    public Map<String, Object> getSession(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM local_session WHERE id = ? AND status < 3", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 列表（对齐平台 /my 语义：缺省排除已归档；projectId 精确 / defaultSpace=仅默认空间 / pinned 置顶优先） */
    public List<Map<String, Object>> listSessions(String projectId, Boolean defaultSpace, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM local_session WHERE status < 3");
        if (status != null) {
            sql.append(" AND status = ").append(status.intValue());
        } else {
            sql.append(" AND status <> 2"); // 缺省排除已归档
        }
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" AND project_id = '").append(projectId.replace("'", "''")).append("'");
        } else if (Boolean.TRUE.equals(defaultSpace)) {
            sql.append(" AND project_id IS NULL");
        }
        sql.append(" ORDER BY pinned DESC, COALESCE(last_message_at, created_at) DESC");
        return jdbc.queryForList(sql.toString());
    }

    /** 会话工作目录（文件工具沙箱根解析用；null=未绑定走全局默认） */
    public String getDirectory(String sessionId) {
        List<String> rows = jdbc.query(
                "SELECT directory FROM local_session WHERE id = ?",
                (rs, i) -> rs.getString(1), sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int updateSession(String id, String title, String agentId, String projectId, String directory) {
        return jdbc.update("""
                        UPDATE local_session SET
                            title = COALESCE(?, title),
                            agent_id = COALESCE(?, agent_id),
                            project_id = COALESCE(?, project_id),
                            directory = COALESCE(?, directory),
                            updated_at = ?
                        WHERE id = ?""",
                title, agentId, projectId, directory, System.currentTimeMillis(), id);
    }

    public int archive(String id) {
        return jdbc.update("UPDATE local_session SET status = 2, time_archived = ?, updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), System.currentTimeMillis(), id);
    }

    public int unarchive(String id) {
        return jdbc.update("UPDATE local_session SET status = 0, time_archived = NULL, updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }

    public int pin(String id, boolean pinned) {
        return jdbc.update("UPDATE local_session SET pinned = ?, updated_at = ? WHERE id = ?",
                pinned ? 1 : 0, System.currentTimeMillis(), id);
    }

    /** 最近一条 assistant 消息写入 metadata（过程时间线持久化，D1-24 收尾） */
    public int updateLastAssistantMetadata(String sessionId, String metadataJson) {
        return jdbc.update("UPDATE local_message SET metadata = ? WHERE id = ("
                + "SELECT id FROM local_message WHERE session_id = ? AND role = 'assistant' "
                + "ORDER BY seq DESC LIMIT 1)", metadataJson, sessionId);
    }

    public int delete(String id) {
        return jdbc.update("UPDATE local_session SET status = 3, updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }

    // ==================== 消息 ====================

    /**
     * 追加消息（client_id 幂等：命中直接返回已落库消息，重放零副作用——对齐平台 D-3 修复语义）。
     * 返回 true=新落库，false=幂等命中。
     */
    public boolean appendMessage(String sessionId, String role, String content,
                                 String clientId, String metadataJson) {
        if (clientId != null && !clientId.isBlank()) {
            List<Map<String, Object>> hit = jdbc.queryForList(
                    "SELECT id FROM local_message WHERE session_id = ? AND client_id = ?", sessionId, clientId);
            if (!hit.isEmpty()) {
                return false;
            }
        }
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM local_message WHERE session_id = ?", Integer.class, sessionId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO local_message (id, session_id, seq, client_id, role, content, metadata, created_at) VALUES (?,?,?,?,?,?,?,?)",
                Ids.newId(), sessionId, seq, clientId, role, content, metadataJson, now);
        jdbc.update("UPDATE local_session SET message_count = message_count + 1, last_message_at = ?, updated_at = ? WHERE id = ?",
                now, now, sessionId);
        // 首条用户消息自动生成标题（对齐平台"标题自动生成"最小实现：截断前 24 字）
        if ("user".equals(role)) {
            jdbc.update("""
                    UPDATE local_session SET title = ? WHERE id = ? AND title = '新对话' AND message_count <= 1
                    """, abbreviate(content, 24), sessionId);
        }
        return true;
    }

    /** 消息列表（seq 升序；camelCase DTO 键，与 @gewu/agent-ui/protocol 对齐） */
    public List<Map<String, Object>> listMessages(String sessionId, int limit) {
        String sql = "SELECT id, session_id, seq, client_id, role, content, metadata, created_at FROM local_message "
                + "WHERE session_id = ? ORDER BY seq ASC" + (limit > 0 ? " LIMIT " + Math.min(limit, 500) : "");
        return jdbc.queryForList(sql, sessionId).stream().map(row -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", row.get("ID") != null ? row.get("ID") : row.get("id"));
            dto.put("sessionId", row.get("SESSION_ID") != null ? row.get("SESSION_ID") : row.get("session_id"));
            dto.put("seq", row.get("SEQ") != null ? row.get("SEQ") : row.get("seq"));
            dto.put("clientId", row.get("CLIENT_ID") != null ? row.get("CLIENT_ID") : row.get("client_id"));
            dto.put("role", row.get("ROLE") != null ? row.get("ROLE") : row.get("role"));
            dto.put("content", row.get("CONTENT") != null ? row.get("CONTENT") : row.get("content"));
            dto.put("metadata", row.get("METADATA") != null ? row.get("METADATA") : row.get("metadata"));
            dto.put("createdAt", asMillis(row.get("CREATED_AT") != null ? row.get("CREATED_AT") : row.get("created_at")));
            return dto;
        }).toList();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "新对话";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** SessionDTO 视图（与 @gewu/agent-ui/protocol SessionDTO 字段对齐） */
    public Map<String, Object> toSessionDTO(Map<String, Object> row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sessionId", row.get("id"));
        dto.put("title", row.get("title"));
        dto.put("type", 1);
        dto.put("typeDesc", "AI 会话");
        dto.put("projectId", row.get("project_id"));
        Object status = row.get("status");
        dto.put("status", status);
        dto.put("statusDesc", "2".equals(String.valueOf(status)) ? "已归档" : "进行中");
        dto.put("isPublic", 0);
        dto.put("pinned", ((Number) row.getOrDefault("pinned", 0)).intValue());
        dto.put("messageCount", ((Number) row.getOrDefault("message_count", 0)).intValue());
        Object lastMsgAt = asMillis(row.get("last_message_at"));
        dto.put("lastMessageAt", lastMsgAt);
        dto.put("agent", row.get("agent_id"));
        dto.put("directory", row.get("directory"));
        dto.put("workspaceId", row.get("workspace_id"));
        dto.put("createdAt", asMillis(row.get("created_at")));
        dto.put("createdBy", "local");
        return dto;
    }

    /** H2 BIGINT → 毫秒（Long 直返；防御性兼容 Timestamp） */
    private static Long asMillis(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Timestamp ts) {
            return ts.getTime();
        }
        return null;
    }
}
