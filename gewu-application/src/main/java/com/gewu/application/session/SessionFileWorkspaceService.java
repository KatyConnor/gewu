package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.tool.FileWorkspaceSpi;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionFileChange;
import com.gewu.domain.session.SessionFileChangeEvent;
import com.gewu.infrastructure.mapper.SessionFileChangeMapper;
import com.gewu.infrastructure.mapper.SessionFileChangeEventMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.DevWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final SessionFileChangeEventMapper fileChangeEventMapper;
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
        snapshotBeforeTurnWrite(ctx, safePath, before);
        recordChange(ctx, safePath, before, content);
        recordWriteEvent(ctx, safePath, before, content);
        sandboxClient.writeFile(b.sandboxId(), toSandboxPath(b.root(), safePath), content);
    }

    /**
     * 记录单次写入的增删事件（git churn 语义）：会话级变更列表按路径累计所有
     * 写入事件，修改/删除行数不再被"净差异"坍缩为 0。turn_seq 取当前活跃回合
     * （回合撤销按其精确回退）；回合外的写入（如面板手工保存）记 0。
     */
    private void recordWriteEvent(ToolContext ctx, String safePath, String before, String after) {
        try {
            long turnSeq = 0;
            if (Boolean.TRUE.equals(activeTurns.get(ctx.getSessionId()))) {
                TurnState ts = turnStates.get(ctx.getSessionId());
                if (ts != null) {
                    turnSeq = ts.seq;
                }
            }
            int[] nd = turnDiffCounts(before, after);
            if (nd[0] == 0 && nd[1] == 0) {
                return; // 内容无变化（如重复写入同一内容）不产生事件
            }
            SessionFileChangeEvent event = new SessionFileChangeEvent();
            event.setId(Ulid.next());
            event.setSessionId(ctx.getSessionId());
            event.setFilePath(safePath);
            event.setTurnSeq(turnSeq);
            event.setAdditions(nd[0]);
            event.setDeletions(nd[1]);
            event.setCreatedAt(System.currentTimeMillis());
            fileChangeEventMapper.insert(event);
        } catch (Exception e) {
            // 事件记录失败不影响写入主链路（统计口径降级为净差异）
            log.warn("记录文件写入事件失败: session={}, path={}, err={}",
                    ctx.getSessionId(), safePath, e.getMessage());
        }
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

    /** 变更文件列表（含 +N/-N 行数统计）：累计该路径所有写入事件（churn 语义，
     *  修改/删除行数不被净差异坍缩）；无事件的历史记录退化为净差异（基线 vs 当前） */
    public List<FileChangeDTO> listChanges(String sessionId) {
        List<SessionFileChange> records = fileChangeMapper.selectList(
                new LambdaQueryWrapper<SessionFileChange>()
                        .eq(SessionFileChange::getSessionId, sessionId)
                        .eq(SessionFileChange::getDeleted, 0)
                        .orderByAsc(SessionFileChange::getUpdatedAt));
        // 按路径累计写入事件
        Map<String, int[]> churnByPath = new java.util.HashMap<>();
        for (SessionFileChangeEvent ev : fileChangeEventMapper.selectList(
                new LambdaQueryWrapper<SessionFileChangeEvent>()
                        .eq(SessionFileChangeEvent::getSessionId, sessionId))) {
            int[] agg = churnByPath.computeIfAbsent(ev.getFilePath(), k -> new int[2]);
            agg[0] += ev.getAdditions() != null ? ev.getAdditions() : 0;
            agg[1] += ev.getDeletions() != null ? ev.getDeletions() : 0;
        }
        List<FileChangeDTO> result = new ArrayList<>();
        for (SessionFileChange r : records) {
            FileChangeDTO dto = new FileChangeDTO();
            dto.setPath(r.getFilePath());
            dto.setChangeType(r.getChangeType());
            dto.setUpdatedAt(r.getUpdatedAt());
            int[] churn = churnByPath.get(r.getFilePath());
            if (churn != null) {
                dto.setAdditions(churn[0]);
                dto.setDeletions(churn[1]);
            } else {
                // 历史记录（事件表上线前）：退化为净差异统计
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
            }
            result.add(dto);
        }
        return result;
    }

    /** 变更差异（before 快照 vs 当前内容，unified diff 文本） */
    public FileDiffDTO diff(String sessionId, String path) {
        SessionFileChange record = requireChange(sessionId, path);
        String current;
        try {
            current = readCurrentContent(sessionId, path);
        } catch (Exception e) {
            // 当前内容不可读（文件已删除/沙箱已销毁）时降级：仅返回 before 快照，前端按 changeType 渲染纯删/提示态
            current = null;
        }
        FileDiffDTO dto = new FileDiffDTO();
        dto.setPath(path);
        dto.setChangeType(record.getChangeType());
        dto.setBefore(record.getBeforeSnapshot());
        dto.setAfter(current);
        dto.setDiffText(current != null ? unifiedDiff(path, record.getBeforeSnapshot(), current) : null);
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

    // ==================== 回合快照（最近一次回复的修改汇总与撤销） ====================

    /** 回合快照：当前回合内每个被修改文件的「回合前内容」（empty=本回合新建，撤销即删除） */
    private static final class TurnState {
        final long seq;
        final ConcurrentHashMap<String, Optional<String>> beforeByPath = new ConcurrentHashMap<>();

        TurnState(long seq) {
            this.seq = seq;
        }
    }

    private final ConcurrentHashMap<String, TurnState> turnStates = new ConcurrentHashMap<>();
    /** 回合进行中标记：AI 写文件期间拒绝撤销，防与引擎写入并发冲突 */
    private final ConcurrentHashMap<String, Boolean> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> undoLocks = new ConcurrentHashMap<>();
    private final AtomicLong turnSeqGen = new AtomicLong();

    /** 回合开始（聊天流开始时调用）：开启新快照使上一回合失效，并标记回合进行中 */
    public void beginTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        turnStates.put(sessionId, new TurnState(turnSeqGen.incrementAndGet()));
        activeTurns.put(sessionId, Boolean.TRUE);
    }

    /** 回合结束（流收尾时调用）：解除进行中标记；快照保留供撤销，直至下一回合 beginTurn */
    public void finishTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeTurns.remove(sessionId);
    }

    /** writeFile 钩子：本回合首次修改某路径时记录回合前内容（putIfAbsent 保证只记第一次） */
    private void snapshotBeforeTurnWrite(ToolContext ctx, String safePath, String before) {
        TurnState ts = turnStates.get(ctx.getSessionId());
        if (ts != null) {
            ts.beforeByPath.putIfAbsent(safePath, Optional.ofNullable(before));
        }
    }

    /** 最近一次回复的修改汇总（文件 + 增删行数）；无快照返回空列表 */
    public List<TurnFileChange> turnChanges(String sessionId) {
        TurnState ts = turnStates.get(sessionId);
        if (ts == null || ts.beforeByPath.isEmpty()) {
            return List.of();
        }
        requireSession(sessionId);
        List<TurnFileChange> result = new ArrayList<>();
        for (Map.Entry<String, Optional<String>> e : ts.beforeByPath.entrySet()) {
            String path = e.getKey();
            String before = e.getValue().orElse(null);
            String current;
            try {
                current = readCurrentContent(sessionId, path);
            } catch (Exception ex) {
                current = null; // 文件已被外部删除：按整文件删除统计
            }
            int[] nd = turnDiffCounts(before, current);
            if (nd[0] == 0 && nd[1] == 0) {
                continue; // 回合内写后与回合前一致（如撤销后重复写入同一内容），不进汇总
            }
            result.add(new TurnFileChange(path, nd[0], nd[1]));
        }
        return result;
    }

    /**
     * 撤销最近一次回复对文件的修改：恢复回合前内容（新建文件则删除），
     * 并同步修正 session_file_change 记录。paths 为空=撤销全部；
     * 仅接受快照白名单内的路径，防任意路径写删。
     */
    public List<UndoResult> undoTurn(String sessionId, List<String> paths) {
        if (Boolean.TRUE.equals(activeTurns.get(sessionId))) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "AI 正在执行中，请等待回合结束后再撤销");
        }
        TurnState ts = turnStates.get(sessionId);
        if (ts == null || ts.beforeByPath.isEmpty()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "没有可撤销的修改（快照已失效或已全部撤销）");
        }
        Session session = requireSession(sessionId);
        Binding b = resolveBindingOf(session);
        List<String> targets = (paths == null || paths.isEmpty())
                ? new ArrayList<>(ts.beforeByPath.keySet())
                : paths;
        List<UndoResult> results = new ArrayList<>();
        Object lock = undoLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            for (String rawPath : targets) {
                String path;
                try {
                    path = sanitizeRelative(rawPath);
                } catch (Exception e) {
                    results.add(UndoResult.fail(rawPath, "非法文件路径"));
                    continue;
                }
                Optional<String> beforeOpt = ts.beforeByPath.get(path);
                if (beforeOpt == null) {
                    results.add(UndoResult.fail(path, "该文件不在最近一次回复的修改范围内"));
                    continue;
                }
                try {
                    String before = beforeOpt.orElse(null);
                    if (before != null) {
                        // 直接写沙箱恢复（不经 writeFile：避免重新快照/刷新变更记录）
                        sandboxClient.writeFile(b.sandboxId(), toSandboxPath(b.root(), path), before);
                    } else {
                        // 本回合新建的文件：撤销即删除
                        sandboxClient.deleteFile(b.sandboxId(), toSandboxPath(b.root(), path));
                    }
                    boolean recordRemoved = adjustChangeRecordAfterUndo(sessionId, path, before);
                    if (recordRemoved) {
                        // 记录已移除（恢复到会话首次基线/新建文件已删除）：清空该路径全部事件
                        fileChangeEventMapper.delete(new LambdaQueryWrapper<SessionFileChangeEvent>()
                                .eq(SessionFileChangeEvent::getSessionId, sessionId)
                                .eq(SessionFileChangeEvent::getFilePath, path));
                    } else {
                        // 仅回退本回合产生的事件（精确按回合序号，不误删更早回合的统计）
                        fileChangeEventMapper.delete(new LambdaQueryWrapper<SessionFileChangeEvent>()
                                .eq(SessionFileChangeEvent::getSessionId, sessionId)
                                .eq(SessionFileChangeEvent::getFilePath, path)
                                .eq(SessionFileChangeEvent::getTurnSeq, ts.seq));
                    }
                    ts.beforeByPath.remove(path);
                    results.add(UndoResult.ok(path));
                    log.info("撤销文件修改: session={}, path={}, mode={}",
                            sessionId, path, before != null ? "restore" : "delete");
                } catch (Exception e) {
                    log.warn("撤销文件修改失败: session={}, path={}, err={}", sessionId, path, e.getMessage());
                    results.add(UndoResult.fail(path, e.getMessage() != null ? e.getMessage() : "撤销失败"));
                }
            }
        }
        return results;
    }

    /** 撤销后同步变更记录：文件恢复到会话首次修改前状态（或新建文件已删除）时移除记录并返回 true，
     *  否则保留记录（统计由事件表回退/实时计算）并返回 false */
    private boolean adjustChangeRecordAfterUndo(String sessionId, String path, String before) {
        SessionFileChange record = fileChangeMapper.selectOne(
                new LambdaQueryWrapper<SessionFileChange>()
                        .eq(SessionFileChange::getSessionId, sessionId)
                        .eq(SessionFileChange::getFilePath, path)
                        .last("LIMIT 1"));
        if (record == null) {
            return false;
        }
        boolean backToBaseline;
        if (before == null) {
            backToBaseline = true; // 新建文件的撤销 = 文件已删除，CREATE 记录一并移除
        } else {
            String current;
            try {
                current = readCurrentContent(sessionId, path);
            } catch (Exception e) {
                current = null;
            }
            backToBaseline = Objects.equals(record.getBeforeSnapshot(), current);
        }
        if (backToBaseline) {
            fileChangeMapper.deleteById(record.getId());
            return true;
        }
        return false;
    }

    /** 回合增删统计（纯函数便于单测）：before 为空视为本回合新建；after 为空视为清空/删除 */
    static int[] turnDiffCounts(String before, String after) {
        if (before == null || before.isEmpty()) {
            return new int[]{splitLines(after).size(), 0};
        }
        if (after == null || after.isEmpty()) {
            return new int[]{0, splitLines(before).size()};
        }
        return diffLineCounts(before, after);
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

    /** 最近一次回复的修改汇总条目（metadata.turnFiles 与 GET /file-changes/turn 共用） */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TurnFileChange {
        private String path;
        private Integer additions;
        private Integer deletions;
    }

    /** 撤销逐路径结果 */
    @lombok.Data
    public static class UndoResult {
        private String path;
        private boolean success;
        private String reason;

        static UndoResult ok(String path) {
            UndoResult r = new UndoResult();
            r.path = path;
            r.success = true;
            return r;
        }

        static UndoResult fail(String path, String reason) {
            UndoResult r = new UndoResult();
            r.path = path;
            r.success = false;
            r.reason = reason;
            return r;
        }
    }
}
