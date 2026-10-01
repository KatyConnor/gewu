package com.gewu.desktop.adapter;

import com.gewu.agent.engine.spi.ExecutionRecord;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桌面持久化适配（D1-9）：引擎 PersistenceService SPI 的 H2 实现。
 * Agent 定义存 local_agent、执行记录存 local_execution；本地工具配置在 D1-14 接入
 * （本地代码型工具经 @ToolProvider 自动注册，此处仅承载 DB 配置型工具，当前返回空表）。
 *
 * @since 1.0.0
 */
@Component
public class DesktopPersistenceService implements PersistenceService {

    private static final RowMapper<AgentSpecRow> AGENT_MAPPER = (rs, i) -> {
        AgentSpecRow r = new AgentSpecRow();
        r.id = rs.getString("id");
        r.name = rs.getString("name");
        r.description = rs.getString("description");
        r.modelProvider = rs.getString("model_provider");
        r.modelName = rs.getString("model_name");
        r.systemPrompt = rs.getString("system_prompt");
        r.status = rs.getInt("status");
        return r;
    };

    private final JdbcTemplate jdbc;

    public DesktopPersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public com.gewu.agent.engine.spi.AgentSpec loadAgent(String agentId) {
        List<AgentSpecRow> rows = jdbc.query(
                "SELECT id, name, description, model_provider, model_name, system_prompt, status FROM local_agent WHERE id = ?",
                AGENT_MAPPER, agentId);
        if (rows.isEmpty()) {
            return null;
        }
        AgentSpecRow r = rows.get(0);
        com.gewu.agent.engine.spi.AgentSpec spec = new com.gewu.agent.engine.spi.AgentSpec();
        spec.setId(r.id);
        spec.setName(r.name);
        spec.setDescription(r.description);
        spec.setModelProvider(r.modelProvider);
        spec.setModelName(r.modelName);
        spec.setSystemPrompt(r.systemPrompt);
        spec.setStatus(r.status);
        return spec;
    }

    @Override
    public List<ToolConfig> loadAgentTools(String agentId) {
        // D1-14：DB 配置型工具接入（本地代码型工具经 @ToolProvider 由 ToolRegistry 自动收集）
        return List.of();
    }

    @Override
    public ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input) {
        ExecutionRecord rec = new ExecutionRecord();
        rec.setId(Ids.newId());
        rec.setAgentId(agentId);
        rec.setSessionId(sessionId);
        rec.setUserId(userId);
        rec.setStatus("RUNNING");
        rec.setInput(input);
        rec.setStartedAt(System.currentTimeMillis());
        jdbc.update("INSERT INTO local_execution (id, agent_id, session_id, user_id, status, input, started_at) VALUES (?,?,?,?,?,?,?)",
                rec.getId(), agentId, sessionId, userId, "RUNNING", input, rec.getStartedAt());
        return rec;
    }

    @Override
    public void completeExecution(String executionId, String output, Integer tokensUsed) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE local_execution SET status='COMPLETED', output=?, tokens_used=?, completed_at=? WHERE id=?",
                output, tokensUsed, now, executionId);
        jdbc.update("UPDATE local_execution SET duration_ms = CAST(completed_at - started_at AS INT) WHERE id = ? AND duration_ms IS NULL",
                executionId);
    }

    @Override
    public void failExecution(String executionId, String errorMessage) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE local_execution SET status='FAILED', error_message=?, completed_at=? WHERE id=?",
                errorMessage, now, executionId);
        jdbc.update("UPDATE local_execution SET duration_ms = CAST(completed_at - started_at AS INT) WHERE id = ? AND duration_ms IS NULL",
                executionId);
    }

    @Override
    public ExecutionRecord getExecution(String executionId) {
        List<ExecutionRecord> rows = jdbc.query(
                "SELECT id, agent_id, session_id, user_id, status, input, output, error_message, tokens_used, started_at, completed_at, duration_ms FROM local_execution WHERE id = ?",
                (rs, i) -> {
                    ExecutionRecord rec = new ExecutionRecord();
                    rec.setId(rs.getString(1));
                    rec.setAgentId(rs.getString(2));
                    rec.setSessionId(rs.getString(3));
                    rec.setUserId(rs.getString(4));
                    rec.setStatus(rs.getString(5));
                    rec.setInput(rs.getString(6));
                    rec.setOutput(rs.getString(7));
                    rec.setErrorMessage(rs.getString(8));
                    rec.setTokensUsed((Integer) rs.getObject(9));
                    rec.setStartedAt(rs.getLong(10));
                    rec.setCompletedAt((Long) rs.getObject(11));
                    rec.setDurationMs((Integer) rs.getObject(12));
                    return rec;
                }, executionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Agent CRUD（桌面 Agent 管理页数据源，Profile /agents 端点使用） */
    public List<com.gewu.agent.engine.spi.AgentSpec> listAgents() {
        return jdbc.query(
                "SELECT id, name, description, model_provider, model_name, system_prompt, status FROM local_agent ORDER BY created_at DESC",
                AGENT_MAPPER).stream().map(r -> {
            com.gewu.agent.engine.spi.AgentSpec spec = new com.gewu.agent.engine.spi.AgentSpec();
            spec.setId(r.id);
            spec.setName(r.name);
            spec.setDescription(r.description);
            spec.setModelProvider(r.modelProvider);
            spec.setModelName(r.modelName);
            spec.setSystemPrompt(r.systemPrompt);
            spec.setStatus(r.status);
            return spec;
        }).toList();
    }

    /** 创建/更新本地 Agent（存在则更新） */
    public String upsertAgent(String id, String name, String description, String modelProvider,
                              String modelName, String systemPrompt, Integer status) {
        long now = System.currentTimeMillis();
        String agentId = id == null || id.isBlank() ? Ids.newId() : id;
        int updated = jdbc.update("UPDATE local_agent SET name=?, description=?, model_provider=?, model_name=?, system_prompt=?, status=?, updated_at=? WHERE id=?",
                name, description, modelProvider, modelName, systemPrompt, status == null ? 1 : status, now, agentId);
        if (updated == 0) {
            jdbc.update("INSERT INTO local_agent (id, name, description, model_provider, model_name, system_prompt, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    agentId, name, description, modelProvider, modelName, systemPrompt, status == null ? 1 : status, now, now);
        }
        return agentId;
    }

    /** Agent 删除（本地库；引用它的会话由模型解析回退机制兜底） */
    public int deleteAgent(String agentId) {
        return jdbc.update("DELETE FROM local_agent WHERE id = ?", agentId);
    }

    /** AgentSpec 行载体（避免与引擎 AgentSpec 类型混淆） */
    private static class AgentSpecRow {
        String id;
        String name;
        String description;
        String modelProvider;
        String modelName;
        String systemPrompt;
        Integer status;
    }
}
