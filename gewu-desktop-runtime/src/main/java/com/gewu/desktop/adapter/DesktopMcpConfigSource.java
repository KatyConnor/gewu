package com.gewu.desktop.adapter;

import com.gewu.agent.engine.mcp.McpServerConfigSource;
import com.gewu.agent.engine.mcp.McpServerDescriptor;
import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 桌面 MCP 配置源（D2-2）：local_mcp_server 表 → 引擎 McpServerManager。
 * stdio 服务器由引擎 StdioMcpClient 拉起子进程；streamable_http 直连；
 * 引擎已实现 2025-03-26 Streamable HTTP 规范（initialized 握手/会话 id 回传）。
 * 工具（listTools）经引擎 McpServerManager 动态发现，控制器透出。
 *
 * @since 1.0.0
 */
@Component
public class DesktopMcpConfigSource implements McpServerConfigSource {

    private final JdbcTemplate jdbc;

    public DesktopMcpConfigSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public McpServerDescriptor loadServer(String serverId) {
        List<McpServerDescriptor> rows = jdbc.query(
                "SELECT id, name, transport, command, args_json, env_json, url, status FROM local_mcp_server WHERE id = ? AND enabled = 1",
                MAPPER, serverId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<McpServerDescriptor> loadAllServers() {
        return jdbc.query(
                "SELECT id, name, transport, command, args_json, env_json, url, status FROM local_mcp_server WHERE enabled = 1 ORDER BY created_at",
                MAPPER);
    }

    private static final org.springframework.jdbc.core.RowMapper<McpServerDescriptor> MAPPER = (rs, i) -> {
        McpServerDescriptor d = new McpServerDescriptor();
        d.setId(rs.getString("id"));
        d.setName(rs.getString("name"));
        d.setTransport(rs.getString("transport"));
        d.setCommand(rs.getString("command"));
        d.setArgs(rs.getString("args_json"));
        d.setEnv(rs.getString("env_json"));
        d.setUrl(rs.getString("url"));
        d.setStatus(rs.getInt("status"));
        return d;
    };

    // ==================== CRUD（控制器用） ====================

    public void upsert(String id, String name, String transport, String command,
                       String argsJson, String envJson, String url, boolean enabled) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("""
                UPDATE local_mcp_server SET name=?, transport=?, command=?, args_json=?, env_json=?, url=?, enabled=?, updated_at=?
                WHERE id=?""",
                name, transport, command, argsJson, envJson, url, enabled ? 1 : 0, now, id);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO local_mcp_server (id, name, transport, command, args_json, env_json, url, enabled, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    id == null || id.isBlank() ? Ids.newId() : id, name, transport, command,
                    argsJson, envJson, url, enabled ? 1 : 0, now, now);
        }
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList(
                "SELECT id, name, transport, command, args_json, env_json, url, enabled FROM local_mcp_server ORDER BY created_at");
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM local_mcp_server WHERE id = ?", id);
    }
}
