package com.gewu.desktop.endpoint;

import com.gewu.desktop.entry.EntryFileResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
