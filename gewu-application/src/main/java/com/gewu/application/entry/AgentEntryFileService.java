package com.gewu.application.entry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.WorkspaceSyncService;
import com.gewu.common.entry.EntryCandidate;
import com.gewu.common.entry.EntryFileSelector;
import com.gewu.common.entry.EntryImportExpander;
import com.gewu.common.entry.EntrySelection;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.session.Session;
import com.gewu.domain.workspace.Workspace;
import com.gewu.domain.workspace.WorkspaceFile;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.WorkspaceFileMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import com.gewu.domain.workspace.WorkspaceSyncState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 入口调度文件服务（平台端，设计文档 56 §6）.
 *
 * <p>全局层：MinIO {@code workspaces/{userId}/{GEWU,AGENTS}.md}（权威源，
 * 会话解析不依赖沙箱存活）；项目层：沙箱卷内项目仓库根（主来源 Git 仓库自带）。
 * 跨层裁决用 {@link EntryFileSelector}（默认项目级优先，双方声明版本才比高低），
 * 与桌面端同一纯函数。
 *
 * <p>注入点：{@code AgentExecutionEngine.buildTask} 与 {@code LongTaskService}
 * 段执行（评审 F-203）；PUT 复用 workspace_file 台账（建行 + M2V 入队，评审 F-202），
 * 沙箱后建时由对账自愈完成种子同步（reconcile M2V 遍历全部已登记行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEntryFileService {

    public static final String NAME_GEWO = "GEWU.md";
    public static final String NAME_AGENTS = "AGENTS.md";
    public static final Set<String> ALLOWED_NAMES = Set.of(NAME_GEWO, NAME_AGENTS);

    /** 单文件读取上限（字节，设计文档 56 §5.1） */
    private static final int READ_CAP_BYTES = 64 * 1024;
    /** 保存内容上限（字节）：入口文件属治理文本，超限应走版本管理而非平台直存 */
    private static final int MAX_CONTENT_BYTES = 512 * 1024;
    /** 裁决缓存容量上限（超限清过期、仍超清空——回源重建安全，CR P1#1 内存放大防护） */
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final long CACHE_TTL_MS = 60_000;

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceFileMapper fileMapper;
    private final SessionMapper sessionMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final SandboxClient sandboxClient;
    private final MinioStorageService storageService;
    private final WorkspaceSyncService syncService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${gewu.entry.max-bytes:32768}")
    private int maxBytes;
    /** 叠加语义（P2）：select=单选（默认）；merge=全局段+项目段拼接，项目声明 override:true 时项目独占 */
    @Value("${gewu.entry.layering:select}")
    private String layering;

    /** 进程内裁决缓存：key=userId|base，TTL 60s；写路径主动失效 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(EntryView view, long expireAt) {}

    /**
     * 裁决视图：content=null 表示无入口文件（source=none）.
     */
    public record EntryView(String content, String source, String metaJson, String upgradeHint) {
        public boolean present() {
            return content != null && !content.isEmpty();
        }
    }

    // ==================== 引擎注入 ====================

    /**
     * 会话级入口指令解析（引擎注入与编排发起共用的公开入口）。
     * 任何失败降级为返回 none 视图，不阻塞会话。
     */
    public EntryView resolveForSession(String userId, String sessionId) {
        try {
            if (userId == null) {
                return noneView();
            }
            Session session = sessionId != null ? sessionMapper.selectById(sessionId) : null;
            // 会话归属校验（CR P2#4 防越权纵深）：非本人会话不读取其目录（降级仅全局层）。
            // Session 无 userId 字段，所有权以创建人（createdBy）为准。
            String directory = session != null && userId.equals(session.getCreatedBy())
                    ? session.getDirectory() : null;
            return resolve(userId, directory);
        } catch (Exception e) {
            log.info("[entry] 会话入口指令解析降级: sessionId={}, err={}", sessionId, e.getMessage());
            return noneView();
        }
    }

    /**
     * 会话任务入口指令解析与填充（AgentExecutionEngine.buildTask / LongTaskService 段执行调用）。
     * 任何失败降级为不注入，不阻塞会话。
     */
    public void applyToTask(com.gewu.agent.engine.core.AgentTask task) {
        try {
            if (task == null || task.getUserId() == null) {
                return;
            }
            EntryView view = resolveForSession(task.getUserId(), task.getSessionId());
            if (view.present()) {
                task.setEntryDirectives(view.content());
                task.setEntryDirectivesSource(view.source());
                task.setEntryDirectivesMeta(view.metaJson());
            }
        } catch (Exception e) {
            log.debug("入口调度指令解析降级: sessionId={}, err={}", task != null ? task.getSessionId() : null, e.getMessage());
        }
    }

    // ==================== 裁决 ====================

    /**
     * 跨层裁决（带缓存）。sessionDirectory 为会话工作目录（卷内相对路径或 /workspace 前缀均兼容），
     * 为空时仅裁决全局层。
     */
    public EntryView resolve(String userId, String sessionDirectory) {
        String base = normalizeBase(sessionDirectory);
        String cacheKey = userId + "|" + (base != null ? base : "");
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.expireAt() > System.currentTimeMillis()) {
                return cached.view();
            }
            cache.remove(cacheKey); // 读时清除过期条目（CR P1#1：防无访问滞留）
        }
        guardCacheCapacity();
        EntryView view = doResolve(userId, base);
        cache.put(cacheKey, new CacheEntry(view, System.currentTimeMillis() + CACHE_TTL_MS));
        return view;
    }

    /** 项目页签裁决（按 projectId 取仓库路径，§6.4 升级提示消费方）。按查看者自己的全局层裁决（成员身份已由 requireProjectAccess 保证）。 */
    public EntryView resolveForProject(String projectId) {
        Project project = requireProjectAccess(projectId);
        return resolve(com.gewu.common.context.UserContext.currentUserId(), project.getRepoLocalPath());
    }

    private EntryView doResolve(String userId, String base) {
        Workspace ws = workspaceByUser(userId);
        EntryCandidate global = ws != null ? loadGlobal(ws) : null;
        EntryCandidate project = null;
        String sandboxId = null;
        if (base != null && ws != null && ws.getDevSandboxId() != null) {
            sandboxId = ws.getDevSandboxId();
            project = loadProjectLayer(sandboxId, base);
        }
        // 叠加语义（P2 §12.1）：merge 下项目声明 override:true 则项目独占
        EntryFileSelector.Layering mode = parseLayering(layering);
        boolean projectOverride = project != null
                && EntryFileSelector.extractOverride(project.getContent());
        EntrySelection sel = EntryFileSelector.select(global, project, mode, projectOverride);

        if (sel.getContent() == null) {
            return new EntryView(null, "none", metaJson("none", null, sel.getReason(), "", 0, false), null);
        }

        // 升级提示（§6.4）：全局带版本且更高，项目级未参与协议或版本较低
        String upgradeHint = buildUpgradeHint(global, project);

        String content = sel.getContent();
        boolean truncated = false;
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) {
            content = truncateToByteLimit(content, maxBytes) + "\n\n…（入口调度指令超限截断）";
            truncated = true;
        }
        String meta = metaJson(sel.getSource(), sel.getVersion(), sel.getReason(),
                sha256Hex(content.getBytes(StandardCharsets.UTF_8)),
                content.getBytes(StandardCharsets.UTF_8).length, truncated);
        return new EntryView(content, sel.getSource(), meta, upgradeHint);
    }

    // ==================== 全局层（MinIO 权威源 + workspace_file 台账） ====================

    /** 编辑器回显：读全局文件（MinIO）。不存在返回 null。 */
    public String readGlobalFile(String userId, String name) {
        requireAllowed(name);
        Workspace ws = workspaceByUser(userId);
        if (ws == null) {
            return null;
        }
        return minioGet(ws.getStoragePath() + name);
    }

    /**
     * 保存全局文件（评审 F-202）：建/更新 workspace_file 台账行 + MinIO 直写 + M2V 入队。
     * 沙箱后建时由对账自愈完成种子同步（reconcile M2V 遍历全部已登记行）。
     * 串行化（CR P2#6）：防并发首存双插台账行。
     */
    @Transactional
    public synchronized void saveGlobalFile(String userId, String name, String content) {
        requireAllowed(name);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        checkContentSize(content);
        checkSecretLeak(content);
        Workspace ws = workspaceByUser(userId);
        if (ws == null) {
            throw new IllegalStateException("用户工作空间不存在，请先初始化");
        }
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        String objectKey = ws.getStoragePath() + name;
        storageService.uploadWorkspaceContent(objectKey, content, "text/markdown");

        WorkspaceFile row = fileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, ws.getId())
                .eq(WorkspaceFile::getFilePath, name)
                .eq(WorkspaceFile::getDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) {
            row = new WorkspaceFile();
            row.setId(com.gewu.common.ulid.Ulid.next());
            row.setWorkspaceId(ws.getId());
            row.setParentId(null);
            row.setFileName(name);
            row.setFileType(2);
            row.setFilePath(name);
            row.setObjectKey(objectKey);
            row.setMimeType("text/markdown");
            row.setFileSize(size);
            row.setChecksum(sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
            row.setVersion(1);
            row.setStatus(1);
            // 显式置 0：logic-delete 字段会被 MP 强制带进 INSERT（null 落库后逻辑删除查询不可见）
            row.setDeleted(0);
            row.setCreatedBy(userId);
            row.setCreatedAt(System.currentTimeMillis());
            row.setUpdatedAt(System.currentTimeMillis());
            fileMapper.insert(row);
            ws.setFileCount((ws.getFileCount() != null ? ws.getFileCount() : 0) + 1);
            ws.setUsedBytes((ws.getUsedBytes() != null ? ws.getUsedBytes() : 0) + size);
        } else {
            long oldSize = row.getFileSize() != null ? row.getFileSize() : 0;
            row.setFileSize(size);
            row.setChecksum(sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
            row.setVersion((row.getVersion() != null ? row.getVersion() : 1) + 1);
            fileMapper.updateById(row);
            long delta = size - oldSize;
            ws.setUsedBytes(Math.max(0, (ws.getUsedBytes() != null ? ws.getUsedBytes() : 0) + delta));
        }
        workspaceMapper.updateById(ws);

        // M2V 入队（幂等）：dev 沙箱在线即同步；离线时登记在案，对账自愈兜底
        syncService.enqueue(ws.getId(), name, WorkspaceSyncState.DIRECTION_M2V);
        evictUser(userId);
        log.info("全局入口文件已保存: userId={}, name={}, bytes={}", userId, name, size);
    }

    // ==================== 项目层（沙箱卷 = Git 仓库根） ====================

    /** 项目文件回显（编辑器/页签）。沙箱离线抛错。仅项目成员可访问（L3-SEC-035 防水平越权）。 */
    public Map<String, Object> readProjectFile(String projectId, String name) {
        requireAllowed(name);
        Project project = requireProjectAccess(projectId);
        Workspace ws = workspaceByUser(project.getOwnerId());
        if (ws == null || ws.getDevSandboxId() == null || project.getRepoLocalPath() == null) {
            throw new IllegalStateException("开发沙箱未就绪，无法读取项目文件");
        }
        try {
            String content = sandboxClient.readFile(ws.getDevSandboxId(),
                    project.getRepoLocalPath() + "/" + name);
            return Map.of("exists", true, "content", content == null ? "" : content,
                    "path", project.getRepoLocalPath() + "/" + name);
        } catch (Exception e) {
            // 读取失败视为文件不存在（clone 后尚未创建）
            return Map.of("exists", false, "content", "",
                    "path", project.getRepoLocalPath() + "/" + name);
        }
    }

    /** 模板初始化：写入项目仓库根（仅 clone 成功后可调——clone 要求目标目录为空，§6.3）。仅项目成员可操作。覆盖前强制快照既有内容（§6.4 承诺兑现）。 */
    public void initProjectFile(String projectId, String name, String content) {
        requireAllowed(name);
        checkContentSize(content);
        checkSecretLeak(content);
        Project project = requireProjectAccess(projectId);
        Workspace ws = workspaceByUser(project.getOwnerId());
        if (ws == null || ws.getDevSandboxId() == null || project.getRepoLocalPath() == null) {
            throw new IllegalStateException("开发沙箱未就绪，无法初始化项目入口文件");
        }
        String targetPath = project.getRepoLocalPath() + "/" + name;
        // 覆盖前强制快照（CR F#7：UI 承诺"可恢复"必须有实现支撑）；快照失败中止覆盖
        try {
            String existing = sandboxClient.readFile(ws.getDevSandboxId(), targetPath);
            if (existing != null && !existing.isEmpty()) {
                String snapKey = ws.getStoragePath() + ".entry-package-snapshots/"
                        + System.currentTimeMillis() + "/" + name;
                storageService.uploadWorkspaceContent(snapKey, existing, "text/markdown");
                log.info("覆盖前快照已存: projectId={}, snapKey={}", projectId, snapKey);
            }
        } catch (Exception e) {
            throw new IllegalStateException("覆盖前快照失败，已中止覆盖（可稍后重试）: " + e.getMessage(), e);
        }
        sandboxClient.writeFile(ws.getDevSandboxId(), targetPath, content);
        evictUser(project.getOwnerId());
        log.info("项目入口文件已初始化: projectId={}, name={}", projectId, name);
    }

    // ==================== 内部工具 ====================

    /** 全局层发现：MinIO storagePath/{GEWU,AGENTS}.md，命中即停。@import 相对卷根展开（沙箱在线时）。 */
    private EntryCandidate loadGlobal(Workspace ws) {
        for (String name : new String[]{NAME_GEWO, NAME_AGENTS}) {
            String content = minioGet(ws.getStoragePath() + name);
            if (content != null && !content.isEmpty()) {
                String expanded = ws.getDevSandboxId() != null
                        ? EntryImportExpander.expand(content, "", sandboxReader(ws.getDevSandboxId(), ""))
                        : content;
                return candidate("user-global", "user-global:" + name, expanded);
            }
        }
        return null;
    }

    /** 项目层发现：沙箱卷 base/{GEWU,AGENTS}.md，命中即停；读取失败按层缺失降级。@import 相对项目目录展开。 */
    private EntryCandidate loadProjectLayer(String sandboxId, String base) {
        for (String name : new String[]{NAME_GEWO, NAME_AGENTS}) {
            try {
                String content = sandboxClient.readFile(sandboxId, base + "/" + name);
                if (content != null && !content.isEmpty()) {
                    if (content.getBytes(StandardCharsets.UTF_8).length > READ_CAP_BYTES) {
                        content = truncateToByteLimit(content, READ_CAP_BYTES);
                    }
                    content = EntryImportExpander.expand(content, base, sandboxReader(sandboxId, base));
                    return candidate("project", "project:" + name, content);
                }
            } catch (Exception e) {
                // 文件不存在或沙箱异常 → 该文件缺失，继续探测下一候选
            }
        }
        return null;
    }

    /** @import 沙箱读取回调（相对路径规范化；失败返回 null 由展开器标注）。 */
    private com.gewu.common.entry.EntryImportExpander.ImportReader sandboxReader(String sandboxId, String baseDir) {
        return (dir, rel) -> {
            String effective = (dir != null && !dir.isBlank()) ? dir : baseDir;
            String path = effective.isBlank() ? rel : effective + "/" + rel;
            try {
                return sandboxClient.readFile(sandboxId, path);
            } catch (Exception e) {
                return null;
            }
        };
    }

    private EntryCandidate candidate(String level, String label, String content) {
        return EntryCandidate.builder()
                .level(level)
                .path(label)
                .version(EntryFileSelector.extractVersion(content))
                .content(content)
                .build();
    }

    private String buildUpgradeHint(EntryCandidate global, EntryCandidate project) {
        if (global == null || global.getVersion() == null) {
            return null;
        }
        if (project == null || project.getContent() == null) {
            return null; // 无项目级文件，无同步对象（初始化走模板通道）
        }
        if (project.getVersion() == null
                || EntryFileSelector.compareVersions(global.getVersion(), project.getVersion()) > 0) {
            return "全局规范已升级至 " + global.getVersion()
                    + "，当前项目文件" + (project.getVersion() == null ? "未声明版本" : "版本 " + project.getVersion())
                    + "，可查看差异或一键同步（限同名文件）";
        }
        return null;
    }

    private String metaJson(String source, String version, String reason, String sha256, int bytes, boolean truncated) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", source);
            meta.put("version", version);
            meta.put("reason", reason);
            meta.put("sha256", sha256);
            meta.put("bytes", bytes);
            meta.put("truncated", truncated);
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"source\":\"" + source + "\",\"reason\":\"" + reason + "\"}";
        }
    }

    /** 会话目录 → 卷内相对路径（剥 /workspace/ 前缀；null=无项目层）。 */
    private static String normalizeBase(String directory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        String d = directory.trim();
        if (d.startsWith("/workspace/")) {
            d = d.substring("/workspace/".length());
        } else if (d.equals("/workspace")) {
            d = "";
        }
        return d.isBlank() ? null : d;
    }

    private Workspace workspaceByUser(String userId) {
        if (userId == null) {
            return null;
        }
        return workspaceMapper.selectOne(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getUserId, userId).last("LIMIT 1"));
    }

    private Project requireProject(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        return project;
    }

    /**
     * 项目访问权校验（CR L3-SEC-035 防 IDOR）：仅项目所有者或成员可读写调度文件。
     * 返回校验通过的项目实体。
     */
    private Project requireProjectAccess(String projectId) {
        Project project = requireProject(projectId);
        String userId = com.gewu.common.context.UserContext.currentUserId();
        if (userId == null) {
            throw com.gewu.common.result.BusinessException.of(com.gewu.common.result.ResultCode.UNAUTHORIZED);
        }
        if (!userId.equals(project.getOwnerId())) {
            Long count = memberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, projectId)
                    .eq(ProjectMember::getUserId, userId));
            if (count == null || count == 0) {
                throw com.gewu.common.result.BusinessException.of(
                        com.gewu.common.result.ResultCode.FORBIDDEN, "仅项目成员可访问项目调度文件");
            }
        }
        return project;
    }

    private String findOwnerId(Project project) {
        return project.getOwnerId();
    }

    private static void checkContentSize(String content) {
        if (content == null) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("内容超过大小上限（512KB），请精简后保存");
        }
    }

    /** 密钥泄漏扫描（§8.5 / P2 豁免期清偿）：命中即拒绝落库。 */
    private static void checkSecretLeak(String content) {
        var hit = com.gewu.common.security.SecretScanUtil.findSecret(content);
        if (hit.isPresent()) {
            throw new IllegalArgumentException(
                    "内容疑似包含" + hit.get() + "，已拒绝保存——请移除凭据后重试（凭据应存放于密钥管理而非调度文件）");
        }
    }

    private String minioGet(String objectKey) {
        try {
            return storageService.getContent(objectKey);
        } catch (Exception e) {
            return null; // 对象不存在 → 该候选缺失
        }
    }

    private static void requireAllowed(String name) {
        if (name == null || !ALLOWED_NAMES.contains(name)) {
            throw new IllegalArgumentException("文件名仅支持: " + ALLOWED_NAMES);
        }
    }

    private static EntryFileSelector.Layering parseLayering(String raw) {
        return "merge".equalsIgnoreCase(raw) ? EntryFileSelector.Layering.MERGE : EntryFileSelector.Layering.SELECT;
    }

    private void evictUser(String userId) {
        String prefix = userId + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 按字节上限截断（不切半个多字节字符）。 */
    /** none 视图工厂（解析降级/无入口文件的统一返回）。 */
    private static EntryView noneView() {
        return new EntryView(null, "none", "{\"source\":\"none\",\"reason\":\"no-entry\"}", null);
    }

    /**
     * 裁决缓存容量护栏（CR P1：过期条目读时清除 + 超上限整体清理，防有界内存放大）。
     */
    private void guardCacheCapacity() {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        cache.values().removeIf(e -> e.expireAt() <= System.currentTimeMillis());
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear(); // 全量清空安全：缓存未命中仅回源重建
        }
    }

    private static String truncateToByteLimit(String content, int maxBytes) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
