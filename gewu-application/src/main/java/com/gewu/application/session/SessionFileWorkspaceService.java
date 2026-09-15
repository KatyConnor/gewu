package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.tool.FileWorkspaceSpi;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionFileChange;
import com.gewu.infrastructure.mapper.SessionFileChangeMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.DevWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话文件工作空间（S9 F3）——引擎内置文件工具的执行后端与变更追踪。
 * <p>路由：会话（session.directory）决定工作空间内的根目录（项目会话=项目
 * 仓库目录，无项目=用户默认空间根）；沙箱取会话绑定工作空间的 dev sandbox
 * （缺失时经 {@link DevWorkspaceService#ensureDevSandboxForUser} 惰性创建）。
 * <p>变更追踪：写类操作首次修改某路径前记录 before 快照（diff 基线），
 * upsert 到 session_file_change（唯一键 session_id+file_path）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionFileWorkspaceService implements FileWorkspaceSpi {

    private final SessionMapper sessionMapper;
    private final SessionFileChangeMapper fileChangeMapper;
    private final SandboxClient sandboxClient;
    private final DevWorkspaceService devWorkspaceService;

    /** 沙箱内工作空间根（ContainerFileService 的 WORKSPACE_ROOT） */
    private static final String WORKSPACE_ROOT = "/workspace";
    private static final Pattern UNSAFE_PATH = Pattern.compile("(^|/)\\.\\.($|/)");

    // ==================== FileWorkspaceSpi（引擎文件工具执行后端） ====================

    @Override
    public String readFile(ToolContext ctx, String path) {
        Binding b = resolveBinding(ctx);
        try {
            return sandboxClient.readFile(b.sandboxId(), toSandboxPath(b.root(), path));
        } catch (Exception e) {
            // 读取失败（不存在/不可读）统一按文件不存在处理，由调用方给出语义
            log.debug("读取沙箱文件失败: sandbox={}, path={}, err={}", b.sandboxId(), path, e.getMessage());
            return null;
        }
    }

    @Override
    public void writeFile(ToolContext ctx, String path, String content) {
        Binding b = resolveBinding(ctx);
        String safePath = sanitizeRelative(path);
        String before = readFile(ctx, path);
        recordChange(ctx, safePath, before, content);
        sandboxClient.writeFile(b.sandboxId(), toSandboxPath(b.root(), safePath), content);
    }

    @Override
    public List<String> listDir(ToolContext ctx, String path) {
        Binding b = resolveBinding(ctx);
        String rel = path == null || path.isBlank() ? "" : sanitizeRelative(path);
        com.gewu.common.dto.sandbox.ExecCommandResponse resp =
                sandboxClient.execCommand(b.sandboxId(), "ls -1 " + quoteShell(toSandboxPath(b.root(), rel)), 15);
        if (resp.getExitCode() == null || resp.getExitCode() != 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "目录不存在或不可读: " + rel);
        }
        List<String> entries = new ArrayList<>();
        for (String line : resp.getStdout().split("\n")) {
            if (!line.isBlank()) {
                entries.add(line.trim());
            }
        }
        return entries;
    }

    // ==================== 控制器 API（变更列表/差异/内容读写） ====================

    /** 变更文件列表（含 +N/-N 行数统计） */
    public List<FileChangeDTO> listChanges(String sessionId) {
        List<SessionFileChange> records = fileChangeMapper.selectList(
                new LambdaQueryWrapper<SessionFileChange>()
                        .eq(SessionFileChange::getSessionId, sessionId)
                        .eq(SessionFileChange::getDeleted, 0)
                        .orderByAsc(SessionFileChange::getUpdatedAt));
        List<FileChangeDTO> result = new ArrayList<>();
        for (SessionFileChange r : records) {
            FileChangeDTO dto = new FileChangeDTO();
            dto.setPath(r.getFilePath());
            dto.setChangeType(r.getChangeType());
            dto.setUpdatedAt(r.getUpdatedAt());
            try {
                String current = readCurrentContent(sessionId, r.getFilePath());
                int[] nd = diffLineCounts(r.getBeforeSnapshot(), current);
                dto.setAdditions(nd[0]);
                dto.setDeletions(nd[1]);
            } catch (Exception e) {
                // 当前内容不可读（如沙箱已销毁）时退化为仅展示记录
                dto.setAdditions(0);
                dto.setDeletions(0);
            }
            result.add(dto);
        }
        return result;
    }

    /** 变更差异（before 快照 vs 当前内容，unified diff 文本） */
    public FileDiffDTO diff(String sessionId, String path) {
        SessionFileChange record = requireChange(sessionId, path);
        String current = readCurrentContent(sessionId, path);
        FileDiffDTO dto = new FileDiffDTO();
        dto.setPath(path);
        dto.setChangeType(record.getChangeType());
        dto.setBefore(record.getBeforeSnapshot());
        dto.setAfter(current);
        dto.setDiffText(unifiedDiff(path, record.getBeforeSnapshot(), current));
        return dto;
    }

    /** 读取当前文件内容（右侧面板「打开」视图） */
    public String readCurrentContent(String sessionId, String path) {
        Session session = requireSession(sessionId);
        Binding b = resolveBindingOf(session);
        try {
            return sandboxClient.readFile(b.sandboxId(), toSandboxPath(b.root(), sanitizeRelative(path)));
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "读取文件失败: " + path);
        }
    }

    /** 保存文件内容（右侧面板「打开」视图编辑保存） */
    @Transactional
    public void saveContent(String sessionId, String path, String content) {
        writeFile(buildToolContextOf(requireSession(sessionId)), path, content != null ? content : "");
    }

    // ==================== 变更记录与差异计算 ====================

    /**
     * 记录变更：首次修改该路径时记录 before 快照并落库；再次修改仅刷新时间
     * （before 基线保持首次修改前状态，幂等 upsert）。
     */
    @Transactional
    protected void recordChange(ToolContext ctx, String safePath, String before, String after) {
        String sessionId = ctx.getSessionId();
        SessionFileChange existing = fileChangeMapper.selectOne(
                new LambdaQueryWrapper<SessionFileChange>()
                        .eq(SessionFileChange::getSessionId, sessionId)
                        .eq(SessionFileChange::getFilePath, safePath)
                        .last("LIMIT 1"));
        if (existing == null) {
            SessionFileChange record = new SessionFileChange();
            record.setId(Ulid.next());
            record.setSessionId(sessionId);
            record.setUserId(ctx.getUserId() != null ? ctx.getUserId() : "system");
            record.setFilePath(safePath);
            record.setChangeType(before == null ? "CREATE" : "MODIFY");
            record.setBeforeSnapshot(before);
            fileChangeMapper.insert(record);
            log.info("会话文件变更记录: session={}, path={}, type={}",
                    sessionId, safePath, record.getChangeType());
        } else {
            existing.setUpdatedAt(System.currentTimeMillis());
            // 首次记录为 CREATE 且内容清空的场景退化为 DELETE 标记
            if ("CREATE".equals(existing.getChangeType()) && (after == null || after.isEmpty())) {
                existing.setChangeType("DELETE");
            }
            fileChangeMapper.updateById(existing);
        }
    }

    /** 行级差异统计：返回 [additions, deletions]（LCS 行对比，文件长度受限） */
    static int[] diffLineCounts(String before, String after) {
        List<String> a = splitLines(before);
        List<String> b = splitLines(after);
        int[][] lcs = lcsTable(a, b);
        int common = lcs[a.size()][b.size()];
        return new int[]{b.size() - common, a.size() - common};
    }

    /** 简易 unified diff 文本（+/- 行标注，供前端文本兜底展示） */
    static String unifiedDiff(String path, String before, String after) {
        List<String> a = splitLines(before);
        List<String> b = splitLines(after);
        StringBuilder sb = new StringBuilder();
        int[][] lcs = lcsTable(a, b);
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (j < b.size() && (i >= a.size() || lcs[i + 1][j] <= lcs[i][j + 1])) {
                sb.append('+').append(b.get(j++)).append('\n');
            } else if (i < a.size()) {
                sb.append('-').append(a.get(i++)).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> splitLines(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line);
        }
        // 末尾空行语义修正：split 尾部产生的空串非真实行
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && !text.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int cap = 4000;
        int n = Math.min(a.size(), cap);
        int m = Math.min(b.size(), cap);
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp;
    }

    // ==================== 工作空间路由 ====================

    private record Binding(String sandboxId, String root) {
    }

    private Binding resolveBinding(ToolContext ctx) {
        Session session = requireSession(ctx.getSessionId());
        return resolveBindingOf(session);
    }

    private Binding resolveBindingOf(Session session) {
        String rootRel = directoryToRelative(session.getDirectory());
        String sandboxId = resolveSandboxId(session);
        return new Binding(sandboxId, rootRel);
    }

    /** 会话目录（/workspace/projects/{id}/repo）→ 工作空间内相对根（projects/{id}/repo） */
    private static String directoryToRelative(String directory) {
        if (directory == null || directory.isBlank()) {
            return "";
        }
        String d = directory.trim();
        if (d.startsWith(WORKSPACE_ROOT)) {
            d = d.substring(WORKSPACE_ROOT.length());
        }
        while (d.startsWith("/")) {
            d = d.substring(1);
        }
        return d;
    }

    private String resolveSandboxId(Session session) {
        String userId = session.getCreatedBy() != null ? session.getCreatedBy() : "system";
        // 统一经 ensureDevSandboxForUser：内部完成探活恢复（DB 状态滞后）、
        // 容器被外部删除后的新建兜底、以及工作空间/沙箱的惰性初始化。
        // 不能因 dev_sandbox_id 非空而直取——该 id 可能已指向被清理的容器。
        return devWorkspaceService.ensureDevSandboxForUser(userId).getSandboxId();
    }

    private Session requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "会话未绑定工作空间（sessionId 缺失）");
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private SessionFileChange requireChange(String sessionId, String path) {
        SessionFileChange record = fileChangeMapper.selectOne(
                new LambdaQueryWrapper<SessionFileChange>()
                        .eq(SessionFileChange::getSessionId, sessionId)
                        .eq(SessionFileChange::getFilePath, sanitizeRelative(path))
                        .last("LIMIT 1"));
        if (record == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该文件在本会话中无变更记录: " + path);
        }
        return record;
    }

    private ToolContext buildToolContextOf(Session session) {
        return ToolContext.builder()
                .sessionId(session.getId())
                .userId(session.getCreatedBy() != null ? session.getCreatedBy() : "system")
                .build();
    }

    /** 相对路径净化：去首尾斜杠、拒绝 .. 越界 */
    private static String sanitizeRelative(String path) {
        if (path == null || path.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件路径不能为空");
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.contains("..") || UNSAFE_PATH.matcher(p).find() || p.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "非法文件路径: " + path);
        }
        return p;
    }

    /** 工作空间相对路径拼接（root 为空=工作空间根） */
    private static String toSandboxPath(String root, String path) {
        String safe = sanitizeRelative(path);
        return root == null || root.isBlank() ? safe : root + "/" + safe;
    }

    /** exec 命令的 shell 引号包裹（CommandValidator 禁用分号等，路径仅含字母数字/点/斜杠/中文） */
    private static String quoteShell(String path) {
        return "'" + path.replace("'", "") + "'";
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class FileChangeDTO {
        private String path;
        private String changeType;
        private Integer additions;
        private Integer deletions;
        private Long updatedAt;
    }

    @lombok.Data
    public static class FileDiffDTO {
        private String path;
        private String changeType;
        private String before;
        private String after;
        private String diffText;
    }
}
