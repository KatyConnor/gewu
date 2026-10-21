package com.gewu.application.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.workspace.Workspace;
import com.gewu.domain.workspace.WorkspaceFile;
import com.gewu.domain.workspace.WorkspaceSyncState;
import com.gewu.infrastructure.mapper.WorkspaceFileMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.mapper.WorkspaceSyncStateMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import com.gewu.application.sandbox.SandboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作空间双向同步服务（设计文档 42 项三，V2 修订）。
 * <p><b>范围裁定（用户审核结论 + 修订）</b>：仅同步文档类文件——
 * ① 项目代码目录（projects/**，Git 克隆区）不入 MinIO，代码由 Git 仓库 push/pull 管理；
 * ② <b>文档扩展名白名单、默认拒绝</b>：开发空间/无项目会话中散落的代码文件
 * （无 projects 前缀的 .go/.py/.js 等及无扩展名文件）一律不同步，
 * 白名单经 {@code gewu.sync.doc-extensions} 配置。
 * <p>两侧归属天然 1:1：同一 Workspace 行持有 dev 沙箱卷（gewu-ws-*）与
 * MinIO 前缀（storagePath=workspaces/{userId}/），无需映射表。
 * <p>触发：① 会话文件工具写入后入队 V2M；② "我的文件"上传/保存后入队 M2V、
 * 删除后入队 DEL_M；③ 周期对账兜底（补偿一次性沙箱写入等无台账场景）。
 * 冲突策略：双侧均有新变化时 last-writer-wins（mtime 新者胜），落败方存
 * conflict 副本；同步失败不影响两侧原功能（status=3 留痕，对账自愈）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceSyncService {

    private static long now() { return System.currentTimeMillis(); }

    /** 排除前缀：项目代码目录（Git 管理） */
    private static final String CODE_PREFIX = "projects/";
    /** 排除路径片段：git 元数据 / 临时文件 */
    private static final String GIT_SEGMENT = "/.git/";
    private static final String TMP_SUFFIX = ".tmp";

    private final WorkspaceSyncStateMapper syncStateMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper fileMapper;
    private final DevWorkspaceService devWorkspaceService;
    private final SandboxClient sandboxClient;
    private final MinioStorageService minioStorageService;

    @Value("${gewu.sync.enabled:true}")
    boolean syncEnabled;
    @Value("${gewu.sync.reconcile-enabled:true}")
    boolean reconcileEnabled;
    @Value("${gewu.sync.batch-size:50}")
    int batchSize;
    @Value("${gewu.sync.max-file-size-mb:100}")
    int maxFileSizeMb;
    /**
     * 文档类扩展名白名单（默认拒绝语义）：白名单之外的文件（含全部代码类与
     * 无扩展名文件）不进入同步通道，确保开发代码不会落入 MinIO。
     */
    @Value("${gewu.sync.doc-extensions:md,markdown,txt,pdf,doc,docx,xls,xlsx,ppt,pptx,csv,tsv,rtf,png,jpg,jpeg,gif,webp,svg}")
    java.util.List<String> docExtensions;

    // ==================== 入队 ====================

    /**
     * 入队一条同步待办（幂等：同路径覆盖方向与状态）。
     * 准入规则（代码走 Git，绝不入 MinIO）：① 排除 projects/** 前缀与 .git 段；
     * ② 仅文档扩展名白名单内的文件可入队，其余（代码/无扩展名）静默跳过。
     */
    public void enqueue(String workspaceId, String path, int direction) {
        if (!syncEnabled || workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        String rel = normalizeRel(path);
        if (rel.isEmpty() || !syncable(rel)) {
            return;
        }
        try {
            String pathHash = pathHash(workspaceId, rel);
            WorkspaceSyncState existing = syncStateMapper.selectOne(
                    new LambdaQueryWrapper<WorkspaceSyncState>()
                            .eq(WorkspaceSyncState::getWorkspaceId, workspaceId)
                            .eq(WorkspaceSyncState::getFilePathHash, pathHash)
                            .last("LIMIT 1"));
            if (existing != null) {
                existing.setDirection(direction);
                existing.setStatus(WorkspaceSyncState.STATUS_PENDING);
                existing.setFailReason(null);
                existing.setUpdatedAt(now());
                syncStateMapper.updateById(existing);
            } else {
                WorkspaceSyncState st = new WorkspaceSyncState();
                st.setId(Ulid.next());
                st.setWorkspaceId(workspaceId);
                st.setFilePath(rel);
                st.setFilePathHash(pathHash);
                st.setDirection(direction);
                st.setStatus(WorkspaceSyncState.STATUS_PENDING);
                st.setCreatedAt(now());
                st.setUpdatedAt(now());
                syncStateMapper.insert(st);
            }
        } catch (Exception e) {
            // 入队失败不影响主链路（对账兜底）
            log.warn("同步入队失败: ws={}, path={}, dir={}, err={}", workspaceId, rel, direction, e.getMessage());
        }
    }

    /** 路径哈希（唯一键用；workspace_id 归一避免跨工作空间同路径互斥） */
    private static String pathHash(String workspaceId, String rel) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((workspaceId + "|" + rel).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 卷内相对路径规范化：剥 /workspace 前缀与首尾斜杠；拒绝 .. 越界（L3-SEC-006） */
    private static String normalizeRel(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.startsWith("/workspace/")) {
            p = p.substring("/workspace/".length());
        } else if (p.equals("/workspace")) {
            return "";
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.contains("..")) {
            return "";
        }
        return p;
    }

    /** 代码目录走 Git 管理：排除 projects/** 与 .git 元数据（硬规则） */
    private static final String ENTRY_PACKAGE_PREFIX = ".entry-package/";

    /** 代码目录走 Git 管理：排除 projects/** 与 .git 元数据（硬规则）；入口包保留前缀同样隔离（设计文档 58 §4.1，N-1 补强） */
    private static boolean excluded(String rel) {
        return rel.startsWith(CODE_PREFIX)
                || rel.startsWith(".git/")
                || rel.contains(GIT_SEGMENT)
                || rel.startsWith(ENTRY_PACKAGE_PREFIX)
                || rel.contains("/" + ENTRY_PACKAGE_PREFIX)
                || rel.endsWith(TMP_SUFFIX);
    }

    /**
     * 同步准入判定：排除规则（代码目录/git 元数据）之外，
     * 还须命中文档扩展名白名单——默认拒绝，代码与未知类型一律不同步。
     */
    private boolean syncable(String rel) {
        if (excluded(rel)) {
            return false;
        }
        int dot = rel.lastIndexOf('.');
        if (dot < 0 || dot < rel.lastIndexOf('/')) {
            return false; // 无扩展名（Makefile/LICENSE 等）或目录成分，视为非文档
        }
        String ext = rel.substring(dot + 1).toLowerCase();
        return docExtensions != null && docExtensions.contains(ext);
    }

    // ==================== 消费（待办排空） ====================

    /** 每 5s 排空待办（单线程调度，串行处理避免同 workspace 并发写） */
    @Scheduled(fixedDelay = 5000)
    public void drainPending() {
        if (!syncEnabled) {
            return;
        }
        List<WorkspaceSyncState> batch = syncStateMapper.selectList(
                new LambdaQueryWrapper<WorkspaceSyncState>()
                        .eq(WorkspaceSyncState::getStatus, WorkspaceSyncState.STATUS_PENDING)
                        .orderByAsc(WorkspaceSyncState::getCreatedAt)
                        .last("LIMIT " + Math.max(1, batchSize)));
        for (WorkspaceSyncState st : batch) {
            process(st);
        }
    }

    private void process(WorkspaceSyncState st) {
        try {
            switch (st.getDirection()) {
                case WorkspaceSyncState.DIRECTION_V2M -> syncV2M(st);
                case WorkspaceSyncState.DIRECTION_M2V -> syncM2V(st);
                case WorkspaceSyncState.DIRECTION_DEL_V -> tombstoneMinio(st);
                case WorkspaceSyncState.DIRECTION_DEL_M -> deleteVolumeFile(st);
                default -> markFailed(st, "未知方向: " + st.getDirection());
            }
        } catch (Exception e) {
            markFailed(st, e.getMessage());
        }
    }

    /** 卷 → MinIO：AI 会话写入的文档落到"我的文件" */
    private void syncV2M(WorkspaceSyncState st) {
        Workspace ws = requireWorkspace(st.getWorkspaceId());
        String sandboxId = resolveSandboxId(ws);
        String objectKey = ws.getStoragePath() + st.getFilePath();
        long volMtime = statVolumeFile(sandboxId, st.getFilePath());
        if (volMtime < 0) {
            // 入队后卷侧已被删：转墓碑
            softDeleteMinioFile(st.getWorkspaceId(), st.getFilePath());
            markDone(st);
            return;
        }
        // 冲突检测：MinIO 侧在最近一次同步（volume_mtime 基线）之后又被编辑过 → 双侧变化
        WorkspaceFile wf = findFileRow(st.getWorkspaceId(), st.getFilePath());
        if (wf != null && wf.getVolumeMtime() != null && volMtime > wf.getVolumeMtime()
                && wf.getUpdatedAt() != null && wf.getUpdatedAt() > wf.getVolumeMtime() * 1000) {
            createConflictCopyM2V(ws, sandboxId, st.getFilePath(), wf);
        }
        byte[] bytes = sandboxClient.downloadFile(sandboxId, "/workspace/" + st.getFilePath());
        if (bytes.length > maxFileSizeMb * 1024L * 1024L) {
            markFailed(st, "超过同步大小上限 " + maxFileSizeMb + "MB");
            return;
        }
        String mime = guessMime(st.getFilePath());
        upsertMinioFile(ws, st.getFilePath(), objectKey, bytes, mime, volMtime);
        markDone(st);
    }

    /** MinIO → 卷：用户上传/编辑的文档推送进 AI 工作区 */
    private void syncM2V(WorkspaceSyncState st) {
        Workspace ws = requireWorkspace(st.getWorkspaceId());
        String sandboxId = resolveSandboxId(ws);
        WorkspaceFile wf = findFileRow(st.getWorkspaceId(), st.getFilePath());
        if (wf == null || wf.getObjectKey() == null) {
            markFailed(st, "MinIO 侧无此文件记录");
            return;
        }
        byte[] bytes = minioStorageService.downloadBytes(wf.getObjectKey());
        long volMtime = statVolumeFile(sandboxId, st.getFilePath());
        // 冲突检测：卷侧在基线（volume_mtime）之后又有变化 → 双侧变化
        if (wf.getVolumeMtime() != null && volMtime >= 0 && volMtime > wf.getVolumeMtime()) {
            // 落败方（MinIO 版本）存卷内冲突副本，主路径以卷为准（last-writer-wins）
            String conflictPath = conflictPath(st.getFilePath());
            sandboxClient.uploadBytes(sandboxId, "/workspace/" + conflictPath, bytes,
                    fileNameOf(conflictPath), guessMime(conflictPath));
            markConflict(st, wf);
            return;
        }
        sandboxClient.uploadBytes(sandboxId, "/workspace/" + st.getFilePath(), bytes,
                fileNameOf(st.getFilePath()), guessMime(st.getFilePath()));
        long newMtime = statVolumeFile(sandboxId, st.getFilePath());
        if (wf != null) {
            wf.setVolumeMtime(newMtime >= 0 ? newMtime : wf.getVolumeMtime());
            wf.setSyncStatus(0);
            wf.setUpdatedAt(now());
            fileMapper.updateById(wf);
        }
        markDone(st);
    }

    /** 卷侧已删除 → MinIO 侧软删（墓碑） */
    private void tombstoneMinio(WorkspaceSyncState st) {
        softDeleteMinioFile(st.getWorkspaceId(), st.getFilePath());
        markDone(st);
    }

    /** MinIO 侧已删除 → 卷侧删除 */
    private void deleteVolumeFile(WorkspaceSyncState st) {
        Workspace ws = requireWorkspace(st.getWorkspaceId());
        String sandboxId = resolveSandboxId(ws);
        sandboxClient.deleteFile(sandboxId, "/workspace/" + st.getFilePath());
        markDone(st);
    }

    // ==================== 周期对账（兜底） ====================

    /**
     * 每 5 分钟对活跃工作空间对账：卷侧（排除代码目录）与 workspace_file 差异入队。
     * 只处理已绑定 dev 沙箱的工作空间，不主动创建沙箱。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void reconcile() {
        if (!syncEnabled || !reconcileEnabled) {
            return;
        }
        List<Workspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getStatus, 1)
                        .isNotNull(Workspace::getDevSandboxId)
                        .orderByDesc(Workspace::getUpdatedAt)
                        .last("LIMIT 20"));
        for (Workspace ws : workspaces) {
            try {
                reconcileOne(ws);
            } catch (Exception e) {
                log.warn("对账失败: ws={}, err={}", ws.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Workspace ws) throws Exception {
        String sandboxId = ws.getDevSandboxId();
        // find 排除代码目录与 .git，输出 相对路径\t大小\tmtime(秒)
        var resp = sandboxClient.execCommand(sandboxId,
                "find /workspace -mindepth 1 \\( -path '/workspace/projects' -o -name .git \\) -prune"
                        + " -o -type f -printf '%P\\t%s\\t%T@\\n' 2>/dev/null | head -2000", 30);
        if (resp.getExitCode() == null || resp.getExitCode() != 0) {
            return;
        }
        Map<String, long[]> volume = new HashMap<>(); // path -> [size, mtimeSec]
        for (String line : resp.getStdout().split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || parts[0].isBlank() || excluded(parts[0])) {
                continue;
            }
            try {
                volume.put(parts[0], new long[]{Long.parseLong(parts[1]),
                        (long) Double.parseDouble(parts[2])});
            } catch (NumberFormatException ignore) {
                // 解析失败的行跳过
            }
        }
        // 卷 → MinIO：卷有而 MinIO 无，或卷比基线新
        List<WorkspaceFile> rows = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, ws.getId())
                        .eq(WorkspaceFile::getFileType, 2)
                        .eq(WorkspaceFile::getDeleted, 0));
        Map<String, WorkspaceFile> byPath = new HashMap<>();
        for (WorkspaceFile r : rows) {
            byPath.put(r.getFilePath(), r);
        }
        for (Map.Entry<String, long[]> e : volume.entrySet()) {
            WorkspaceFile r = byPath.get(e.getKey());
            boolean missing = r == null || r.getObjectKey() == null;
            boolean neverSynced = r == null || r.getVolumeMtime() == null;
            boolean newer = r != null && r.getVolumeMtime() != null && e.getValue()[1] > r.getVolumeMtime();
            if (missing || neverSynced || newer) {
                enqueue(ws.getId(), e.getKey(), WorkspaceSyncState.DIRECTION_V2M);
            }
        }
        // MinIO → 卷：MinIO 有（曾同步过或从未落卷）而卷侧缺失
        for (WorkspaceFile r : rows) {
            if (!volume.containsKey(r.getFilePath())) {
                enqueue(ws.getId(), r.getFilePath(), WorkspaceSyncState.DIRECTION_M2V);
            }
        }
    }

    // ==================== 工具方法 ====================

    private Workspace requireWorkspace(String workspaceId) {
        Workspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) {
            throw new IllegalStateException("工作空间不存在: " + workspaceId);
        }
        return ws;
    }

    private String resolveSandboxId(Workspace ws) {
        // 已绑定且存活直接用；失败/缺失时走 ensure（惰性创建）
        if (ws.getDevSandboxId() != null && !ws.getDevSandboxId().isBlank()) {
            try {
                SandboxDTO dto = sandboxClient.getSandbox(ws.getDevSandboxId());
                if (dto != null && "running".equalsIgnoreCase(dto.getStatus())) {
                    return ws.getDevSandboxId();
                }
            } catch (Exception ignore) {
                // 探活失败走 ensure
            }
        }
        SandboxDTO ensured = devWorkspaceService.ensureDevSandboxForUser(ws.getUserId());
        return ensured.getSandboxId();
    }

    /** stat 卷侧文件，返回 mtime（秒）；不存在返回 -1 */
    private long statVolumeFile(String sandboxId, String rel) {
        try {
            var resp = sandboxClient.execCommand(sandboxId,
                    "stat -c %Y " + shellQuote("/workspace/" + rel), 5);
            if (resp.getExitCode() != null && resp.getExitCode() == 0 && !resp.getStdout().isBlank()) {
                return Long.parseLong(resp.getStdout().trim().split("\n")[0].trim());
            }
        } catch (NumberFormatException e) {
            log.warn("解析 stat 输出失败: path={}, err={}", rel, e.getMessage());
        } catch (Exception e) {
            log.warn("stat 卷侧文件失败: path={}, err={}", rel, e.getMessage());
        }
        return -1;
    }

    private WorkspaceFile findFileRow(String workspaceId, String rel) {
        return fileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getFilePath, rel)
                .eq(WorkspaceFile::getFileType, 2)
                .eq(WorkspaceFile::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /** V2M：MinIO 侧落对象 + workspace_file 行 upsert（含目录链补建） */
    private void upsertMinioFile(Workspace ws, String rel, String objectKey,
                                 byte[] bytes, String mime, long volMtime) {
        minioStorageService.uploadWorkspaceBytes(objectKey, bytes, mime);
        WorkspaceFile wf = findFileRow(ws.getId(), rel);
        if (wf == null) {
            String parentId = ensureDirChain(ws, parentOf(rel));
            wf = new WorkspaceFile();
            wf.setId(Ulid.next());
            wf.setWorkspaceId(ws.getId());
            wf.setParentId(parentId);
            wf.setFileName(fileNameOf(rel));
            wf.setFileType(2);
            wf.setFilePath(rel);
            wf.setVersion(1);
            wf.setStatus(1);
            wf.setCreatedAt(now());
            wf.setCreatedBy("sync");
            wf.setChecksum("sha256-" + Integer.toHexString(java.util.Arrays.hashCode(bytes)));
        } else {
            wf.setVersion((wf.getVersion() != null ? wf.getVersion() : 1) + 1);
        }
        wf.setObjectKey(objectKey);
        wf.setMimeType(mime);
        wf.setFileSize((long) bytes.length);
        wf.setVolumeMtime(volMtime);
        wf.setSyncStatus(0);
        wf.setUpdatedAt(now());
        wf.setUpdatedBy("sync");
        if (wf.getCreatedAt() == null) {
            fileMapper.insert(wf);
        } else {
            fileMapper.updateById(wf);
        }
    }

    /** 补建目录链（V2M 卷内子目录在 workspace_file 树中不存在时），返回末端目录 id */
    private String ensureDirChain(Workspace ws, String dirRel) {
        if (dirRel == null || dirRel.isEmpty()) {
            return null;
        }
        String[] segs = dirRel.split("/");
        String parentId = null;
        String acc = "";
        for (String seg : segs) {
            acc = acc.isEmpty() ? seg : acc + "/" + seg;
            WorkspaceFile dir = fileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getWorkspaceId, ws.getId())
                    .eq(WorkspaceFile::getFilePath, acc)
                    .eq(WorkspaceFile::getFileType, 1)
                    .eq(WorkspaceFile::getDeleted, 0)
                    .last("LIMIT 1"));
            if (dir == null) {
                dir = new WorkspaceFile();
                dir.setId(Ulid.next());
                dir.setWorkspaceId(ws.getId());
                dir.setParentId(parentId);
                dir.setFileName(seg);
                dir.setFileType(1);
                dir.setFilePath(acc);
                dir.setVersion(1);
                dir.setStatus(1);
                dir.setCreatedAt(now());
                dir.setUpdatedAt(now());
                dir.setCreatedBy("sync");
                fileMapper.insert(dir);
            }
            parentId = dir.getId();
        }
        return parentId;
    }

    /** M2V 冲突：把 MinIO 当前版本落到卷内冲突副本，主路径保持卷版本 */
    private void createConflictCopyM2V(Workspace ws, String sandboxId, String rel, WorkspaceFile wf) {
        try {
            byte[] minioBytes = minioStorageService.downloadBytes(wf.getObjectKey());
            String conflictPath = conflictPath(rel);
            sandboxClient.uploadBytes(sandboxId, "/workspace/" + conflictPath, minioBytes,
                    fileNameOf(conflictPath), guessMime(conflictPath));
            if (wf.getSyncStatus() == null || wf.getSyncStatus() != 2) {
                wf.setSyncStatus(2);
                wf.setUpdatedAt(now());
                fileMapper.updateById(wf);
            }
            log.info("同步冲突，MinIO 版本已存卷内副本: ws={}, path={}, copy={}",
                    ws.getId(), rel, conflictPath);
        } catch (Exception e) {
            log.warn("冲突副本生成失败: ws={}, path={}, err={}", ws.getId(), rel, e.getMessage());
        }
    }

    private void softDeleteMinioFile(String workspaceId, String rel) {
        WorkspaceFile wf = findFileRow(workspaceId, rel);
        if (wf != null) {
            wf.setStatus(2);
            wf.setDeleted(1);
            wf.setUpdatedAt(now());
            wf.setUpdatedBy("sync");
            fileMapper.updateById(wf);
            if (wf.getObjectKey() != null) {
                minioStorageService.deleteObject(wf.getObjectKey());
            }
        }
    }

    private void markDone(WorkspaceSyncState st) {
        st.setStatus(WorkspaceSyncState.STATUS_DONE);
        st.setUpdatedAt(now());
        syncStateMapper.updateById(st);
    }

    private void markConflict(WorkspaceSyncState st, WorkspaceFile wf) {
        st.setStatus(WorkspaceSyncState.STATUS_CONFLICT);
        st.setUpdatedAt(now());
        syncStateMapper.updateById(st);
        if (wf != null) {
            wf.setSyncStatus(2);
            fileMapper.updateById(wf);
        }
    }

    private void markFailed(WorkspaceSyncState st, String reason) {
        st.setStatus(WorkspaceSyncState.STATUS_FAILED);
        st.setFailReason(reason != null && reason.length() > 480 ? reason.substring(0, 480) : reason);
        st.setUpdatedAt(now());
        syncStateMapper.updateById(st);
        log.warn("同步失败: ws={}, path={}, dir={}, reason={}",
                st.getWorkspaceId(), st.getFilePath(), st.getDirection(), st.getFailReason());
    }

    private static String conflictPath(String rel) {
        int dot = rel.lastIndexOf('.');
        String stamp = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
        return dot > rel.lastIndexOf('/') ? rel.substring(0, dot) + ".conflict-" + stamp + rel.substring(dot)
                : rel + ".conflict-" + stamp;
    }

    private static String fileNameOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx >= 0 ? rel.substring(idx + 1) : rel;
    }

    private static String parentOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx > 0 ? rel.substring(0, idx) : "";
    }

    private static String guessMime(String rel) {
        String mime = URLConnection.guessContentTypeFromName(rel);
        return mime != null ? mime : "application/octet-stream";
    }

    /** shell 单引号包裹（路径经 normalizeRel 净化，不含单引号场景兜底剔除） */
    private static String shellQuote(String path) {
        return "'" + path.replace("'", "") + "'";
    }
}
