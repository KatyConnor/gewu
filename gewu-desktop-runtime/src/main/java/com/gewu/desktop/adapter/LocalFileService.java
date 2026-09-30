package com.gewu.desktop.adapter;

import com.gewu.desktop.config.DesktopProps;
import com.gewu.desktop.security.PathGuard;
import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地文件服务（D1-15 核心）：内置文件工具（read_file/write_file/edit_file/list_dir）
 * 与文件变更端点的共享实现。{@code LocalFileWorkspaceSpi} 以 ToolContext 包装本服务，
 * 文件变更端点以 sessionId 直调。
 * <p>安全（ADR-009 SPI 自加固三件套）：
 * ① {@link PathGuard} 路径沙箱；② 写授权（三级权限模式，弹窗流 D1-21 接入）；
 * ③ 调用级审计（local_audit 哈希链列）。写路径同时埋变更记录（§7.5，唯一写入口全覆盖）。
 *
 * @since 1.0.0
 */
@Service
public class LocalFileService {

    private final DesktopProps props;
    private final JdbcTemplate jdbc;
    private final com.gewu.desktop.security.ApprovalBroker approvalBroker;

    public LocalFileService(DesktopProps props, JdbcTemplate jdbc,
                            com.gewu.desktop.security.ApprovalBroker approvalBroker) {
        this.props = props;
        this.jdbc = jdbc;
        this.approvalBroker = approvalBroker;
    }

    // ==================== 工作区根解析 ====================

    /** 根解析：会话 directory ＞ 全局默认（gewu.desktop.workspace-root） */
    public Path rootFor(String sessionId) throws IOException {
        String dir = sessionId == null ? null : sessionDirectory(sessionId);
        if (dir == null || dir.isBlank()) {
            dir = props.getWorkspaceRoot();
        }
        Path root = Path.of(dir);
        Files.createDirectories(root);
        return root;
    }

    private String sessionDirectory(String sessionId) {
        List<String> rows = jdbc.query(
                "SELECT directory FROM local_session WHERE id = ?",
                (rs, i) -> rs.getString(1), sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 文件操作（相对工作区根） ====================

    /** 读文件；不存在返回 null */
    public String readFile(String sessionId, String path) throws IOException {
        Path target = PathGuard.secureResolve(rootFor(sessionId), path);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return null;
        }
        audit(sessionId, "read_file", "ALLOW", path, null);
        return Files.readString(target);
    }

    /** 写文件（整体覆盖）：授权 → 快照 → 落盘 → 变更记录 → 审计 */
    public void writeFile(String sessionId, String path, String content) throws IOException {
        writeFile(sessionId, path, content, true);
    }

    /** 写文件（requireApproval=false 供编辑器人工保存路径，弹窗仅拦截模型写入） */
    public void writeFile(String sessionId, String path, String content, boolean requireApproval) throws IOException {
        Path target = PathGuard.secureResolve(rootFor(sessionId), path);
        if (Files.isDirectory(target)) {
            throw new SecurityException("目标是目录，不能作为文件写入: " + path);
        }
        authorizeWrite(sessionId, "write_file", path, requireApproval);
        String before = Files.exists(target) ? Files.readString(target) : null;
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content == null ? "" : content);
        recordChange(sessionId, path, before, content);
        audit(sessionId, "write_file", "ALLOW", path, "bytes=" + (content == null ? 0 : content.length()));
    }

    /** 列目录（返回文件/子目录名清单；目录加 / 后缀，对齐 ls -1 可读性） */
    public List<String> listDir(String sessionId, String path) throws IOException {
        Path target = PathGuard.secureResolve(rootFor(sessionId), path);
        if (!Files.exists(target)) {
            return List.of();
        }
        if (!Files.isDirectory(target)) {
            return List.of(target.getFileName().toString());
        }
        audit(sessionId, "list_dir", "ALLOW", path, null);
        try (Stream<Path> stream = Files.list(target)) {
            return stream.map(p -> Files.isDirectory(p)
                            ? p.getFileName().toString() + "/"
                            : p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    // ==================== 变更记录（§7.5，对齐平台 V39 语义） ====================

    private void recordChange(String sessionId, String path, String before, String after) {
        long now = System.currentTimeMillis();
        String changeType = before == null ? "CREATE" : "MODIFY";
        int[] delta = countChanges(before, after);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_file_change WHERE session_id = ? AND file_path = ?",
                Integer.class, sessionId, path);
        if (existing != null && existing > 0) {
            // 首改基线不可变；类型：原为 CREATE（新文件）保持，否则 MODIFY
            jdbc.update("""
                    UPDATE local_file_change SET change_type = CASE WHEN before_snapshot IS NULL THEN 'CREATE' ELSE 'MODIFY' END,
                        additions = ?, deletions = ?, updated_at = ?
                    WHERE session_id = ? AND file_path = ?""",
                    delta[0], delta[1], now, sessionId, path);
        } else {
            jdbc.update("""
                    INSERT INTO local_file_change (session_id, file_path, change_type, before_snapshot, additions, deletions, updated_at)
                    VALUES (?,?,?,?,?,?,?)""",
                    sessionId, path, changeType, before, delta[0], delta[1], now);
        }
    }

    /** 变更行数（简化袋diff：按行多重集合比对；平台端 LCS 精确口径为后续对齐项） */
    static int[] countChanges(String before, String after) {
        List<String> b = before == null ? List.of() : Arrays.asList(before.split("\n", -1));
        List<String> a = after == null ? List.of() : Arrays.asList(after.split("\n", -1));
        Map<String, Long> bc = b.stream().collect(Collectors.groupingBy(x -> x, Collectors.counting()));
        Map<String, Long> ac = a.stream().collect(Collectors.groupingBy(x -> x, Collectors.counting()));
        int added = 0;
        int removed = 0;
        for (Map.Entry<String, Long> e : ac.entrySet()) {
            long d = e.getValue() - bc.getOrDefault(e.getKey(), 0L);
            if (d > 0) added += (int) Math.min(d, Integer.MAX_VALUE - added);
        }
        for (Map.Entry<String, Long> e : bc.entrySet()) {
            long d = e.getValue() - ac.getOrDefault(e.getKey(), 0L);
            if (d > 0) removed += (int) Math.min(d, Integer.MAX_VALUE - removed);
        }
        return new int[]{added, removed};
    }

    /** 变更列表（端点层） */
    public List<Map<String, Object>> listChanges(String sessionId) {
        return jdbc.queryForList("""
                SELECT file_path, change_type, additions, deletions, updated_at
                FROM local_file_change WHERE session_id = ? ORDER BY updated_at DESC
                """, sessionId).stream().map(row -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("path", col(row, "FILE_PATH", "file_path"));
            dto.put("changeType", col(row, "CHANGE_TYPE", "change_type"));
            dto.put("additions", ((Number) col(row, "ADDITIONS", "additions")).intValue());
            dto.put("deletions", ((Number) col(row, "DELETIONS", "deletions")).intValue());
            dto.put("updatedAt", col(row, "UPDATED_AT", "updated_at"));
            return dto;
        }).toList();
    }

    /** diff 审查数据（before 快照 + 当前内容；diffText 由端点层按需生成，客户端 InlineDiffView 用 before/after） */
    public Map<String, Object> diff(String sessionId, String path) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT change_type, before_snapshot FROM local_file_change WHERE session_id = ? AND file_path = ?",
                sessionId, path);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Path target = PathGuard.secureResolve(rootFor(sessionId), path);
        String after = Files.exists(target) && !Files.isDirectory(target) ? Files.readString(target) : null;
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("path", path);
        dto.put("changeType", col(row, "CHANGE_TYPE", "change_type"));
        dto.put("before", col(row, "BEFORE_SNAPSHOT", "before_snapshot"));
        dto.put("after", after);
        dto.put("diffText", null);
        return dto;
    }

    /** 编辑保存（FileEditorPanel Ctrl+S 写回；人工动作免弹窗，变更记录同入口） */
    public void saveContent(String sessionId, String path, String content) throws IOException {
        writeFile(sessionId, path, content, false);
    }

    // ==================== 授权与审计 ====================

    /** 写授权（D1-21）：READ_ONLY 拒绝；WORKSPACE_WRITE 弹窗 diff 确认（alwaysAllow 白名单跳过）；
     *  FULL_AUTO 自动授权。requireApproval=false 供编辑器人工保存路径。 */
    private void authorizeWrite(String sessionId, String tool, String path, boolean requireApproval) {
        String mode = props.getPermissionMode();
        if ("READ_ONLY".equals(mode)) {
            audit(sessionId, tool, "DENY", path, "READ_ONLY 模式禁止写入");
            throw new SecurityException("当前为只读模式（READ_ONLY），写入被拒绝");
        }
        if ("WORKSPACE_WRITE".equals(mode) && requireApproval) {
            // before/after 快照供弹窗 diff；阻塞等待用户决策（超时拒绝）
            String before = null;
            try {
                Path target = PathGuard.secureResolve(rootFor(sessionId), path);
                before = Files.exists(target) && !Files.isDirectory(target) ? Files.readString(target) : null;
            } catch (IOException ignored) {
                // 读不到基线按 null（新建）处理
            }
            boolean approved = approvalBroker.request(sessionId, tool, path, before, null);
            audit(sessionId, tool, approved ? "ALLOW" : "DENY", path,
                    approved ? "用户弹窗授权" : "用户拒绝或超时");
            if (!approved) {
                throw new SecurityException("用户拒绝了本次写入: " + path);
            }
            return;
        }
        // FULL_AUTO：自动授权（审计留痕）
    }

    private void audit(String sessionId, String tool, String decision, String target, String detail) {
        try {
            long now = System.currentTimeMillis();
            String prev = jdbc.queryForObject(
                    "SELECT hash FROM local_audit ORDER BY created_at DESC LIMIT 1", String.class);
            String payload = String.format("%s|%s|%s|%s|%s", sessionId, tool, decision, target, now);
            String hash = sha256((prev == null ? "" : prev) + payload);
            jdbc.update("""
                    INSERT INTO local_audit (id, tool, action, decision, operator, detail, prev_hash, hash, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    Ids.newId(), tool, "FILE_OP", target, decision, sessionId, detail, prev, hash, now);
        } catch (Exception e) {
            // 审计失败不阻塞业务（异常记录进日志即可）
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "NA";
        }
    }

    private static Object col(Map<String, Object> row, String upper, String lower) {
        return row.containsKey(upper) ? row.get(upper) : row.get(lower);
    }
}
