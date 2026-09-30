package com.gewu.desktop.endpoint;

import com.gewu.desktop.llm.DesktopKeyVault;
import com.gewu.desktop.llm.DesktopModelCatalog;
import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎管理面（桌面特有，§10.1）：模型供应商的注册与查询。
 * <p>Key 经 {@link DesktopKeyVault} 仅驻内存；供应商元数据（baseUrl/models）落 local_model_provider
 * （重启后由渲染层重新提交 Key 恢复，主进程 SecretVault 持久化密文）。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/engine")
public class EngineAdminController {

    public record ProviderRequest(String code, String displayName, String baseUrl,
                                  String modelsJson, String apiKey) {
    }

    private final JdbcTemplate jdbc;
    private final DesktopKeyVault keyVault;
    private final com.gewu.agent.engine.tool.ToolRegistry toolRegistry;

    public EngineAdminController(JdbcTemplate jdbc, DesktopKeyVault keyVault,
                                 com.gewu.agent.engine.tool.ToolRegistry toolRegistry) {
        this.jdbc = jdbc;
        this.keyVault = keyVault;
        this.toolRegistry = toolRegistry;
    }

    /** 注册/更新模型供应商（幂等 upsert；apiKey 仅驻内存） */
    @PostMapping("/providers")
    public Map<String, Object> register(@RequestBody ProviderRequest req) {
        if (req.code() == null || req.code().isBlank() || req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new IllegalArgumentException("code 与 baseUrl 必填");
        }
        long now = System.currentTimeMillis();
        String modelsJson = req.modelsJson() == null || req.modelsJson().isBlank()
                ? "[{\"name\":\"default\",\"displayName\":\"默认模型\",\"description\":\"\"}]" : req.modelsJson();
        int updated = jdbc.update(
                "UPDATE local_model_provider SET display_name=?, base_url=?, models_json=?, enabled=1, updated_at=? WHERE code=?",
                req.displayName(), req.baseUrl(), modelsJson, now, req.code());
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO local_model_provider (code, display_name, base_url, models_json, enabled, created_at, updated_at)
                            VALUES (?,?,?,?,1,?,?)""",
                    req.code(), req.displayName(), req.baseUrl(), modelsJson, now, now);
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            keyVault.put(req.code(), req.apiKey());
        }
        return Map.of("ok", true, "code", req.code());
    }

    /** 删除供应商（禁用行 + 清除内存 Key；Agent 引用将回退到其余可用供应商） */
    @org.springframework.web.bind.annotation.DeleteMapping("/providers/{code}")
    public Map<String, Object> delete(@org.springframework.web.bind.annotation.PathVariable String code) {
        jdbc.update("UPDATE local_model_provider SET enabled = 0, updated_at = ? WHERE code = ?",
                System.currentTimeMillis(), code);
        keyVault.remove(code);
        return Map.of("ok", true, "code", code);
    }

    /** 已注册工具清单（注册验证：内置文件工具 + @ToolProvider 本地工具） */
    @GetMapping("/tools")
    public List<String> tools() {
        return toolRegistry.listDefinitions().stream()
                .map(com.gewu.agent.engine.llm.model.ToolDefinition::getName)
                .sorted()
                .toList();
    }

    /** 供应商清单（Key 脱敏：仅返回是否已配置） */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return jdbc.queryForList(
                        "SELECT code, display_name, base_url, models_json, enabled FROM local_model_provider WHERE enabled = 1")
                .stream().map(row -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("code", row.get("CODE") != null ? row.get("CODE") : row.get("code"));
                    dto.put("displayName", row.get("DISPLAY_NAME") != null ? row.get("DISPLAY_NAME") : row.get("display_name"));
                    dto.put("baseUrl", row.get("BASE_URL") != null ? row.get("BASE_URL") : row.get("base_url"));
                    dto.put("modelsJson", row.get("MODELS_JSON") != null ? row.get("MODELS_JSON") : row.get("models_json"));
                    dto.put("keyConfigured", keyVault.has(String.valueOf(
                            row.get("CODE") != null ? row.get("CODE") : row.get("code"))));
                    return dto;
                }).toList();
    }
}
