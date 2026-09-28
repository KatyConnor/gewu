package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowDataSourceBridge;
import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据库查询节点（51 号 §2.6，53 号 §3.6）：只读 select 先行——
 * SQL 形状守卫（仅 SELECT/WITH 开头、禁分号多语句与 SQL 注释、强制行数帽）
 * 后经 {@link FlowDataSourceBridge} 在受控只读数据源上执行。
 * <p>config：dataSourceId（必填）、sql（必填，支持 ${变量} 占位——仅作文本
 * 替换用于受控报表场景，参数化绑定列 P7 评估）、maxRows（默认 100，上限 1000）。
 */
@Component
public class DatabaseHandler extends AiNodeHandler {

    static final int MAX_ROWS_LIMIT = 1000;

    private final ObjectProvider<FlowDataSourceBridge> bridgeProvider;

    public DatabaseHandler(ObjectProvider<FlowDataSourceBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    @Override
    public String type() {
        return "database";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("dataSourceId", "sql");
    }

    @Override
    protected String nodeLabel() {
        return "数据库查询";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowDataSourceBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            context.complete(false, "数据源桥未接入（宿主未提供 FlowDataSourceBridge 实现），无法执行 database 节点");
            return;
        }
        completeSafely(context, () -> {
            String sql = render(str(config, "sql"), context.variables()).trim();
            String guardFailure = guard(sql);
            if (guardFailure != null) {
                context.complete(false, "SQL 守卫拒绝: " + guardFailure);
                return;
            }
            int maxRows = (int) Math.min(TaskHandler.parseLong(config.get("maxRows"), 100), MAX_ROWS_LIMIT);
            FlowDataSourceBridge.QueryResult result = bridge.query(
                    str(config, "dataSourceId"), sql, maxRows);
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("rows", result.rows());
            output.put("rowCount", result.rowCount());
            output.put("durationMs", result.durationMs());
            context.complete(outputJson(output));
        });
    }

    /** SQL 形状守卫：返回 null 表示通过，否则为拒绝原因 */
    static String guard(String sql) {
        if (sql.isEmpty()) {
            return "SQL 为空";
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return "仅允许 SELECT/WITH 查询";
        }
        if (sql.contains(";")) {
            return "禁止分号（多语句）";
        }
        if (lower.contains("--") || lower.contains("/*")) {
            return "禁止 SQL 注释";
        }
        // 提取关键 DML/DDL 词（词边界，单引号字符串字面量内不扫描——防误伤
        // where note = 'delete me' 之类的值文本），防 WITH 子查询内夹带写语句
        for (String keyword : List.of("insert", "update", "delete", "drop", "alter", "create",
                "truncate", "grant", "revoke", "merge", "call", "do")) {
            if (containsWord(lower, keyword)) {
                return "检测到写操作关键字 " + keyword.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean containsWord(String lower, String keyword) {
        int idx = 0;
        boolean inLiteral = false;
        while (idx < lower.length()) {
            char c = lower.charAt(idx);
            if (c == '\'') {
                inLiteral = !inLiteral;
                idx++;
                continue;
            }
            if (inLiteral) {
                idx++;
                continue;
            }
            if (lower.startsWith(keyword, idx)) {
                char before = idx > 0 ? lower.charAt(idx - 1) : ' ';
                int end = idx + keyword.length();
                char after = end < lower.length() ? lower.charAt(end) : ' ';
                if (!Character.isLetterOrDigit(before) && before != '_'
                        && !Character.isLetterOrDigit(after) && after != '_') {
                    return true;
                }
                idx = end;
                continue;
            }
            idx++;
        }
        return false;
    }

    static String toJsonMap(Map<String, Object> map) {
        return VeloflowJson.MAPPER.valueToTree(map).toString();
    }
}
