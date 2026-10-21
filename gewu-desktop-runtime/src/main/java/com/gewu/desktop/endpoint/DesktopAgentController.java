package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.DesktopPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import com.gewu.agent.engine.spi.AgentSpec;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎 API Profile：Agent 域（D2-8，与平台 /agents 同构）。
 * 本地库 CRUD；删除受引用的 Agent 时对话模型解析自动回退到其余可用供应商/Agent。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/agents")
public class DesktopAgentController {

    private final DesktopPersistenceService persistence;

    /** 智能体定义文件目录(~/.gewu/agents,DB 为真源,文件为同步导出的可读副本) */
    private Path agentsDir() throws java.io.IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".gewu", "agents");
        java.nio.file.Files.createDirectories(dir);
        return dir;
    }

    /** CRUD 后同步导出/删除定义文件(可读、可备份、可手工迁移) */
    private void syncAgentFile(String agentId, String name, String description,
                               String modelProvider, String modelName, String systemPrompt, Integer status) {
        try {
            Path file = agentsDir().resolve(agentId + ".json");
            if (status != null && status == 0) {
                java.nio.file.Files.deleteIfExists(file);
                return;
            }
            String json = "{\n"
                    + "  \"agentId\": \"" + agentId + "\",\n"
                    + "  \"name\": \"" + (name == null ? "" : name.replace("\\", "\\\\").replace("\"", "\\\"\"")) + "\",\n"
                    + "  \"description\": \"" + (description == null ? "" : description.replace("\"", "\\\"\"")) + "\",\n"
                    + "  \"modelProvider\": \"" + (modelProvider == null ? "" : modelProvider) + "\",\n"
                    + "  \"modelName\": \"" + (modelName == null ? "" : modelName) + "\",\n"
                    + "  \"systemPrompt\": \"" + (systemPrompt == null ? "" : systemPrompt.replace("\n", "\\n")) + "\",\n"
                    + "  \"status\": " + (status == null ? 1 : status) + "\n"
                    + "}";
            java.nio.file.Files.writeString(file, json);
        } catch (Exception e) {
            // 文件同步失败不影响 DB 真源(端点响应不暴露)
        }
    }

    public DesktopAgentController(DesktopPersistenceService persistence) {
        this.persistence = persistence;
    }

    public record AgentUpsertRequest(String name, String description, String modelProvider,
                                     String modelName, String systemPrompt, Integer status) {
    }

    private Map<String, Object> toDTO(AgentSpec spec) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("agentId", spec.getId());
        dto.put("name", spec.getName());
        dto.put("description", spec.getDescription());
        dto.put("modelProvider", spec.getModelProvider());
        dto.put("modelName", spec.getModelName());
        dto.put("systemPrompt", spec.getSystemPrompt());
        dto.put("status", spec.getStatus());
        dto.put("statusDesc", spec.getStatus() != null && spec.getStatus() == 1 ? "启用" : "停用");
        return dto;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return persistence.listAgents().stream().map(this::toDTO).toList();
    }

    @GetMapping("/{agentId}")
    public Map<String, Object> get(@PathVariable String agentId) {
        AgentSpec spec = persistence.loadAgent(agentId);
        if (spec == null) {
            throw new IllegalArgumentException("Agent 不存在: " + agentId);
        }
        return toDTO(spec);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AgentUpsertRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        String id = persistence.upsertAgent(null, req.name(), req.description(),
                req.modelProvider(), req.modelName(), req.systemPrompt(), req.status());
        syncAgentFile(id, req.name(), req.description(), req.modelProvider(), req.modelName(),
                req.systemPrompt(), req.status() == null ? 1 : req.status());
        return toDTO(persistence.loadAgent(id));
    }

    /** 全量更新（PUT 语义：客户端发送完整资源） */
    @PutMapping("/{agentId}")
    public Map<String, Object> update(@PathVariable String agentId,
                                      @RequestBody AgentUpsertRequest req) {
        if (persistence.loadAgent(agentId) == null) {
            throw new IllegalArgumentException("Agent 不存在: " + agentId);
        }
        persistence.upsertAgent(agentId, req.name(), req.description(),
                req.modelProvider(), req.modelName(), req.systemPrompt(), req.status());
        syncAgentFile(agentId, req.name(), req.description(), req.modelProvider(), req.modelName(),
                req.systemPrompt(), req.status() == null ? 1 : req.status());
        return toDTO(persistence.loadAgent(agentId));
    }

    @DeleteMapping("/{agentId}")
    public Map<String, Object> delete(@PathVariable String agentId) {
        int n = persistence.deleteAgent(agentId);
        if (n > 0) {
            try {
                java.nio.file.Files.deleteIfExists(agentsDir().resolve(agentId + ".json"));
            } catch (Exception ignored) {
                // 文件清理失败不影响 DB 删除结果
            }
        }
        return Map.of("ok", n > 0);
    }

}
