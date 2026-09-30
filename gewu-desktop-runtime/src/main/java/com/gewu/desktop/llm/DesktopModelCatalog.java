package com.gewu.desktop.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型清单（GET /ai/models 数据源）：读取 local_model_provider 表。
 * 独立于 Provider 实现（stub profile 下同样可用）。
 *
 * @since 1.0.0
 */
@Component
public class DesktopModelCatalog {

    private final JdbcTemplate jdbc;

    public DesktopModelCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelEntry> listModels() {
        return jdbc.query(
                "SELECT code, display_name, models_json FROM local_model_provider WHERE enabled = 1",
                (rs, i) -> {
                    ModelEntry e = new ModelEntry();
                    e.provider = rs.getString(1);
                    e.displayName = rs.getString(2);
                    e.modelsJson = rs.getString(3);
                    return e;
                });
    }

    public static class ModelEntry {
        public String provider;
        public String displayName;
        public String modelsJson;
    }

    /** 该供应商模型清单中的第一个模型名（Agent 未固化模型名时的解析兜底） */
    public String firstModelName(String providerCode) {
        List<ModelEntry> entries = listModels();
        for (ModelEntry e : entries) {
            if (e.provider.equals(providerCode) && e.modelsJson != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode arr =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.modelsJson);
                    if (arr.isArray() && arr.size() > 0 && arr.get(0).hasNonNull("name")) {
                        return arr.get(0).get("name").asText();
                    }
                } catch (Exception ignored) {
                    // JSON 异常按无模型处理
                }
            }
        }
        return null;
    }
}
