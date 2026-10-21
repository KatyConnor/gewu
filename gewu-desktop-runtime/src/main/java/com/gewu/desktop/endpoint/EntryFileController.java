package com.gewu.desktop.endpoint;

import com.gewu.desktop.entry.EntryFileResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 引擎 API Profile：入口调度文件读写（设计文档 56 §7.2）.
 * <p>全局层 = ~/.gewu/{GEWU,AGENTS}.md；项目层 = {projectDirectory}/{GEWU,AGENTS}.md。
 * 文件名白名单，杜绝路径逃逸；供渲染层设置面板「调度入口文件」编辑器使用。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/entry-file")
public class EntryFileController {

    private static final List<String> ALLOWED_NAMES = List.of("GEWU.md", "AGENTS.md");

    private final EntryFileResolver resolver;
    private final com.gewu.desktop.adapter.DesktopProjectStore projectStore;

    public EntryFileController(EntryFileResolver resolver,
                               com.gewu.desktop.adapter.DesktopProjectStore projectStore) {
        this.resolver = resolver;
        this.projectStore = projectStore;
    }

    public record SaveRequest(String scope, String name, String project, String content) {
    }

    /** 裁决预览：与系统提示词注入同源的 resolve 结果（含 source/meta/升级提示）。 */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam(required = false) String project) {
        var r = resolver.resolve(project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", r.present());
        out.put("source", r.source());
        out.put("content", r.content());
        out.put("meta", r.metaJson());
        out.put("upgradeHint", r.upgradeHint());
        return out;
    }

    /** 读单文件（编辑器回显）。scope=global 读 ~/.gewu，scope=project 读项目目录。 */
    @GetMapping
    public Map<String, Object> read(@RequestParam String scope,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String project) {
        Path file = fileFor(scope, name, project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toString());
        out.put("exists", Files.isRegularFile(file));
        try {
            out.put("content", Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : "");
        } catch (Exception e) {
            out.put("content", "");
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 写单文件（编辑器保存）。目录不存在自动创建（全局 ~/.gewu 既有约定内）。 */
    @PostMapping
    public Map<String, Object> save(@RequestBody SaveRequest req) {
        if (req == null || req.content() == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        // 密钥泄漏扫描（§8.5 / P2 豁免期清偿）：命中即拒绝落盘
        var hit = com.gewu.common.security.SecretScanUtil.findSecret(req.content());
        if (hit.isPresent()) {
            throw new IllegalArgumentException(
                    "内容疑似包含" + hit.get() + "，已拒绝保存——请移除凭据后重试");
        }
        // 大小上限（与平台侧对齐，CR P2#18）
        if (req.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new IllegalArgumentException("内容超过大小上限（512KB），请精简后保存");
        }
        Path file = fileFor(req.scope(), req.name(), req.project());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, req.content(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("保存失败: " + e.getMessage(), e);
        }
        return Map.of("ok", true, "path", file.toString());
    }

    // ==================== 入口包导入（设计文档 58 §4，P3-3 桌面形态） ====================

    public record ImportPackageRequest(String sourcePath) {}

    private static final long IMPORT_MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final long IMPORT_MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /** 导入本地入口包（目录或 .zip）到 ~/.gewu/：入口文件平铺 + 资源平铺，逐文件密钥扫描。 */
    @PostMapping("/import-package")
    public Map<String, Object> importPackage(@RequestBody ImportPackageRequest req) throws Exception {
        if (req == null || req.sourcePath() == null || req.sourcePath().isBlank()) {
            throw new IllegalArgumentException("sourcePath 不能为空");
        }
        Path source = Path.of(req.sourcePath().trim());
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("路径不存在: " + source);
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (source.toString().toLowerCase().endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(source))) {
                ZipEntry e;
                long total = 0;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    String rel = normalizeEntryName(e.getName());
                    if (rel == null) {
                        continue;
                    }
                    byte[] content = zis.readAllBytes();
                    total += content.length;
                    if (content.length > IMPORT_MAX_FILE_BYTES || total > IMPORT_MAX_TOTAL_BYTES) {
                        throw new IllegalArgumentException("包超过大小上限（单文件 2MB / 总量 200MB）");
                    }
                    entries.put(rel, content);
                }
            }
        } else if (Files.isDirectory(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = normalizeEntryName(source.relativize(p).toString());
                    if (rel == null || rel.isBlank()) {
                        continue;
                    }
                    if (Files.size(p) > IMPORT_MAX_FILE_BYTES) {
                        throw new IllegalArgumentException("单文件超过上限（2MB）: " + rel);
                    }
                    entries.put(rel, Files.readAllBytes(p));
                }
            }
        } else {
            throw new IllegalArgumentException("sourcePath 须为目录或 .zip 文件");
        }
        String entryName = null;
        for (var e : entries.entrySet()) {
            var secret = com.gewu.common.security.SecretScanUtil.findSecret(
                    new String(e.getValue(), StandardCharsets.UTF_8));
            if (secret.isPresent()) {
                throw new IllegalArgumentException("文件 " + e.getKey() + " 疑似包含" + secret.get() + "，已拒绝导入");
            }
            if (e.getKey().equals("GEWU.md") || e.getKey().equals("AGENTS.md")) {
                entryName = e.getKey();
            }
        }
        if (entryName == null) {
            throw new IllegalArgumentException("包根层缺少入口文件（GEWU.md 或 AGENTS.md）");
        }
        Path home = Path.of(System.getProperty("user.home"), ".gewu");
        for (var e : entries.entrySet()) {
            Path target = home.resolve(e.getKey()).normalize();
            if (!target.startsWith(home)) {
                throw new IllegalArgumentException("非法路径: " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileCount", entries.size());
        out.put("entryFile", entryName);
        return out;
    }

    /** zip 条目名规范化：剥 ./ 前缀；绝对路径/.. /反斜杠返回 null（zip slip 拒绝）。 */
    private static String normalizeEntryName(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/") || p.isBlank()
                || java.util.Arrays.stream(p.split("/")).anyMatch(".."::equals)) {
            return null;
        }
        return p;
    }

    private Path fileFor(String scope, String name, String project) {
        if (name == null || !ALLOWED_NAMES.contains(name)) {
            throw new IllegalArgumentException("文件名仅支持: " + ALLOWED_NAMES);
        }
        if ("global".equals(scope)) {
            return Path.of(System.getProperty("user.home"), ".gewu", name);
        }
        if ("project".equals(scope)) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("项目层读写必须提供 project 目录");
            }
            // 目录限定（CR L3-SEC-006 相邻）：仅允许已注册项目的绑定目录，拒绝任意路径
            String trimmed = project.trim();
            boolean registered = projectStore.listProjects().stream()
                    .anyMatch(p -> trimmed.equals(String.valueOf(p.get("directory"))));
            if (!registered) {
                throw new IllegalArgumentException("项目目录未注册，仅允许项目绑定的本地目录");
            }
            return Path.of(trimmed, name);
        }
        throw new IllegalArgumentException("scope 仅支持 global/project");
    }
}
