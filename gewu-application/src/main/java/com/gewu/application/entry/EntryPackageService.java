package com.gewu.application.entry;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.security.SecretScanUtil;
import com.gewu.domain.project.Project;
import com.gewu.domain.workspace.Workspace;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 入口包导入服务（设计文档 58 §4）：zip/单文件 → 全局空间（保留前缀）或项目仓库.
 *
 * <p>四重校验（包结构/zip 安全/密钥扫描/冲突报告）+ 保留前缀落位：
 * 全局资源统一 MinIO {@code workspaces/{uid}/.entry-package/<相对结构>}（与用户
 * 文件命名空间物理隔离，不建台账不入同步）；入口文件本体落全局入口位或项目仓库根。
 * 服务侧流式解压（ZipInputStream 逐条目），三重限额流式即时生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryPackageService {

    public static final int MAX_ENTRIES = 5000;
    public static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private final WorkspaceMapper workspaceMapper;
    private final ProjectMapper projectMapper;
    private final MinioStorageService storageService;
    private final SandboxClient sandboxClient;
    private final AgentEntryFileService agentEntryFileService;

    /** 导入报告。 */
    public record ImportReport(int fileCount, String entryFile, List<String> overwritten, String target) {}

    /**
     * 导入 zip 入口包.
     *
     * @param target   "global" 或 "project"
     * @param zipBytes zip 原始字节
     */
    public ImportReport importFromZip(String userId, String target, String projectId, byte[] zipBytes) {
        Map<String, byte[]> entries = readZipEntries(zipBytes);
        String entryName = detectEntryFile(entries.keySet());
        return place(userId, target, projectId, entryName, entries);
    }

    /** 导入单文件（作为入口文件本体）。 */
    public ImportReport importSingleEntry(String userId, String target, String projectId,
                                          String name, byte[] content) {
        if (!name.equals(AgentEntryFileService.NAME_GEWO) && !name.equals(AgentEntryFileService.NAME_AGENTS)) {
            throw new IllegalArgumentException("入口文件仅支持: GEWU.md / AGENTS.md");
        }
        return place(userId, target, projectId, name, Map.of(name, content));
    }

    // ==================== 解包与校验 ====================

    /** 服务侧流式解压 + 四重前置校验（包结构/zip slip/限额/密钥扫描）。 */
    private Map<String, byte[]> readZipEntries(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("包内文件数超过上限（" + MAX_ENTRIES + "）");
                }
                String rel = normalizeEntryName(entry.getName());
                if (rel == null || rel.isBlank()) {
                    continue;
                }
                byte[] content = zis.readAllBytes();
                if (content.length > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("单文件超过上限（2MB）: " + rel);
                }
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("解包总量超过上限（200MB）");
                }
                var secret = SecretScanUtil.findSecret(new String(content, StandardCharsets.UTF_8));
                if (secret.isPresent()) {
                    throw new IllegalArgumentException(
                            "文件 " + rel + " 疑似包含" + secret.get() + "，已拒绝导入");
                }
                entries.put(rel, content);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("入口包解析失败（请确认上传的是合法 zip）: " + e.getMessage(), e);
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("入口包为空");
        }
        return entries;
    }

    /** 入口文件检测：根层 GEWU.md/AGENTS.md 恰好一个（并存整体拒绝——批量导入入口歧义代价更高）。 */
    private static String detectEntryFile(Set<String> relPaths) {
        boolean hasGewu = relPaths.contains(AgentEntryFileService.NAME_GEWO);
        boolean hasAgents = relPaths.contains(AgentEntryFileService.NAME_AGENTS);
        if (hasGewu && hasAgents) {
            throw new IllegalArgumentException("包内根层同时存在 GEWU.md 与 AGENTS.md，请二选一后再导入");
        }
        if (hasGewu) {
            return AgentEntryFileService.NAME_GEWO;
        }
        if (hasAgents) {
            return AgentEntryFileService.NAME_AGENTS;
        }
        throw new IllegalArgumentException("包根层缺少入口文件（GEWU.md 或 AGENTS.md）");
    }

    /** zip 条目名规范化：剥 ./ 前缀；绝对路径/.. 段/反斜杠返回 null（zip slip 拒绝）。 */
    private static String normalizeEntryName(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.startsWith("/") || p.contains("\\") || p.isBlank()) {
            return null;
        }
        return com.gewu.agent.engine.entry.NestedEntryFinder.normalizePath(p);
    }

    // ==================== 落位 ====================

    private ImportReport place(String userId, String target, String entryName,
                               String projectId, Map<String, byte[]> entries) {
        if ("project".equals(target)) {
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("target=project 必须提供 projectId");
            }
            return placeProject(projectId, entryName, entries);
        }
        if ("global".equals(target)) {
            return placeGlobal(userId, entryName, entries);
        }
        throw new IllegalArgumentException("target 仅支持 global/project");
    }

    /** 全局落位：入口文件 → storagePath 根；资源 → `.entry-package/` 保留前缀（无台账/无同步）。 */
    private ImportReport placeGlobal(String userId, String entryName, Map<String, byte[]> entries) {
        Workspace ws = workspaceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getUserId, userId).last("LIMIT 1"));
        if (ws == null) {
            throw new IllegalStateException("用户工作空间不存在，请先初始化");
        }
        List<String> overwritten = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String rel = e.getKey();
            String objectKey = rel.equals(entryName)
                    ? ws.getStoragePath() + rel
                    : ws.getStoragePath() + ".entry-package/" + rel;
            if (storageService.prefixHasObjects(objectKey)) {
                overwritten.add(rel);
            }
            storageService.uploadWorkspaceBytes(objectKey, e.getValue(), contentType(rel));
            log.info("入口包文件已导入全局: userId={}, key={}, bytes={}", userId, objectKey, e.getValue().length);
        }
        agentEntryFileService.evictUser(userId);
        return new ImportReport(entries.size(), entryName + "（全局）", overwritten, "global");
    }

    /** 项目落位：全部写入沙箱卷仓库根（Git 管理，模型文件工具直达）。 */
    private ImportReport placeProject(String projectId, String entryName, Map<String, byte[]> entries) {
        Project project = agentEntryFileService.requireProjectAccess(projectId);
        Workspace ws = workspaceByUser(project.getOwnerId());
        if (ws == null || ws.getDevSandboxId() == null || project.getRepoLocalPath() == null) {
            throw new IllegalStateException("开发沙箱未就绪，无法导入到项目");
        }
        List<String> overwritten = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String targetPath = project.getRepoLocalPath() + "/" + e.getKey();
            String existing = null;
            try {
                existing = sandboxClient.readFile(ws.getDevSandboxId(), targetPath);
            } catch (Exception ignored) {
                // 读取失败视为不存在
            }
            if (existing != null && !existing.isEmpty()) {
                overwritten.add(e.getKey());
            }
            sandboxClient.uploadBytes(ws.getDevSandboxId(), targetPath, e.getValue(),
                    e.getKey(), contentType(e.getKey()));
        }
        agentEntryFileService.evictUser(project.getOwnerId());
        return new ImportReport(entries.size(), entryName + "（项目）", overwritten, "project");
    }

    private Workspace workspaceByUser(String userId) {
        if (userId == null) {
            return null;
        }
        return workspaceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getUserId, userId).last("LIMIT 1"));
    }

    private static String contentType(String name) {
        return name.endsWith(".md") ? "text/markdown" : "application/octet-stream";
    }
}
