package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.DesktopMcpConfigSource;
import com.gewu.agent.engine.mcp.McpServerManager;
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
 * 引擎 API Profile：MCP 服务器域（D2-2，与平台 /mcp-servers 同构）。
 * CRUD + 工具动态发现（引擎 McpServerManager：stdio 子进程拉起 /
 * Streamable HTTP 直连，2025-03-26 规范）。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/mcp-servers")
public class DesktopMcpController {

    private final DesktopMcpConfigSource source;
    private final McpServerManager manager;

    public DesktopMcpController(DesktopMcpConfigSource source, McpServerManager manager) {
        this.source = source;
        this.manager = manager;
    }

    public record McpUpsertRequest(String name, String transport, String command,
                                   String argsJson, String envJson, String url, Boolean enabled) {
    }

    private Map<String, Object> toDTO(Map<String, Object> row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        Object id = row.getOrDefault("ID", row.get("id"));
        dto.put("serverId", id);
        dto.put("name", row.getOrDefault("NAME", row.get("name")));
        dto.put("transport", row.getOrDefault("TRANSPORT", row.get("transport")));
        dto.put("command", row.getOrDefault("COMMAND", row.get("command")));
        dto.put("argsJson", row.getOrDefault("ARGS_JSON", row.get("args_json")));
        dto.put("envJson", row.getOrDefault("ENV_JSON", row.get("env_json")));
        dto.put("url", row.getOrDefault("URL", row.get("url")));
        dto.put("enabled", ((Number) row.getOrDefault("ENABLED", row.getOrDefault("enabled", 1))).intValue() == 1);
        return dto;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return source.listAll().stream().map(this::toDTO).toList();
    }

    /** 注册/更新（幂等；重启引擎或更新后工具重新发现） */
    @PostMapping
    public Map<String, Object> create(@RequestBody McpUpsertRequest req) {
        validate(req);
        source.upsert(null, req.name(), req.transport(), req.command(),
                req.argsJson(), req.envJson(), req.url(), req.enabled() == null || req.enabled());
        return Map.of("ok", true);
    }

    @PutMapping("/{serverId}")
    public Map<String, Object> update(@PathVariable String serverId, @RequestBody McpUpsertRequest req) {
        validate(req);
        source.upsert(serverId, req.name(), req.transport(), req.command(),
                req.argsJson(), req.envJson(), req.url(), req.enabled() == null || req.enabled());
        return Map.of("ok", true);
    }

    @DeleteMapping("/{serverId}")
    public Map<String, Object> delete(@PathVariable String serverId) {
        source.delete(serverId);
        try {
            manager.disconnect(serverId);
        } catch (Exception ignored) {
            // 连接缓存可能不存在
        }
        return Map.of("ok", true);
    }

    /** 工具动态发现（McpServerManager 连接缓存；stdio 拉起子进程） */
    @GetMapping("/{serverId}/tools")
    public List<Map<String, Object>> tools(@PathVariable String serverId) {
        return manager.listTools(serverId).stream().map(t -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("name", t.getName());
            dto.put("description", t.getDescription());
            return dto;
        }).toList();
    }

    private void validate(McpUpsertRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        String t = req.transport() == null ? "stdio" : req.transport();
        if (!List.of("stdio", "streamable_http", "sse").contains(t)) {
            throw new IllegalArgumentException("transport 非法: " + t);
        }
        if ("stdio".equals(t) && (req.command() == null || req.command().isBlank())) {
            throw new IllegalArgumentException("stdio 传输需要 command");
        }
        if (!"stdio".equals(t) && (req.url() == null || req.url().isBlank())) {
            throw new IllegalArgumentException(t + " 传输需要 url");
        }
    }
}
