package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.LocalFileService;
import com.gewu.desktop.security.PathGuard;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 引擎 API Profile：本地技能库（D2-9，与平台 /skills 同构的最小集）。
 * 技能 = 工作区 .gewu/skills/*.md（Markdown 指令文件）——project.context 工具
 * 与系统提示词装载共用同一目录约定（docs/design/42 §7.4）。
 * 平台技能库的安装走协同形态资源同步（Phase 2 后半段）。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/skills")
public class DesktopSkillController {

    private final LocalFileService fileService;

    public DesktopSkillController(LocalFileService fileService) {
        this.fileService = fileService;
    }

    private Path skillsDir(String sessionId) throws IOException {
        Path root = fileService.rootFor(sessionId);
        Path dir = PathGuard.secureResolve(root, ".gewu/skills");
        Files.createDirectories(dir);
        return dir;
    }

    /** 技能清单（工作区 .gewu/skills/*.md） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String sessionId) throws IOException {
        Path dir = skillsDir(sessionId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".md")).sorted()::iterator) {
                Map<String, Object> dto = new LinkedHashMap<>();
                String name = p.getFileName().toString().replaceFirst("\\.md$", "");
                dto.put("name", name);
                dto.put("fileName", p.getFileName().toString());
                try {
                    dto.put("size", Files.size(p));
                    String head = Files.readString(p);
                    dto.put("preview", head.substring(0, Math.min(head.length(), 120)));
                } catch (Exception ignored) {
                    // 读取失败占位
                }
                out.add(dto);
            }
        }
        return out;
    }

    public record SkillContentRequest(String content) {
    }

    /** 安装/更新技能（写入 .gewu/skills/{name}.md；同名校验防路径注入） */
    @PostMapping("/{name}")
    public Map<String, Object> install(@RequestParam(required = false) String sessionId,
                                       @PathVariable String name,
                                       @RequestBody SkillContentRequest req) throws IOException {
        String safe = name.replace('\\', '/').trim();
        if (safe.isBlank() || safe.contains("..") || safe.startsWith("/") || !safe.matches("[\\w\\-\\u4e00-\\u9fff]+(\\.[\\w\\-]+)?")) {
            throw new IllegalArgumentException("技能名非法（仅允许字母数字下划线连字符中文）: " + name);
        }
        Path dir = skillsDir(sessionId);
        Files.writeString(dir.resolve(safe + ".md"), req.content() == null ? "" : req.content());
        return Map.of("ok", true, "name", safe);
    }

    /** 读取技能内容 */
    @GetMapping("/{name}/content")
    public Map<String, Object> content(@RequestParam(required = false) String sessionId,
                                       @PathVariable String name) throws IOException {
        Path dir = skillsDir(sessionId);
        Path file = PathGuard.secureResolve(dir, name + ".md");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("技能不存在: " + name);
        }
        return Map.of("name", name, "content", Files.readString(file));
    }

    /** 卸载技能 */
    @DeleteMapping("/{name}")
    public Map<String, Object> uninstall(@RequestParam(required = false) String sessionId,
                                         @PathVariable String name) throws IOException {
        Path dir = skillsDir(sessionId);
        Path file = PathGuard.secureResolve(dir, name + ".md");
        Files.deleteIfExists(file);
        return Map.of("ok", true);
    }
}
