package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.LocalFileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 引擎 API Profile：会话文件变更域（D1-23，§7.5）。
 * 与平台 SessionFileChangeController 同构——web 端 FileEditorPanel/Monaco 面板零适配复用。
 * /turn + /undo（回合快照撤销）待平台 V47 收敛后接入。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/file-changes")
public class DesktopFileChangeController {

    private final LocalFileService fileService;

    public DesktopFileChangeController(LocalFileService fileService) {
        this.fileService = fileService;
    }

    /** 变更文件列表（+N/-N，驱动「N 个文件已更改」chip 与面板列表） */
    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String sessionId) {
        return fileService.listChanges(sessionId);
    }

    /** 变更差异（审查视图：before 快照 + 当前内容） */
    @GetMapping("/diff")
    public Map<String, Object> diff(@PathVariable String sessionId,
                                    @RequestParam String path) throws Exception {
        Map<String, Object> dto = fileService.diff(sessionId, path);
        if (dto == null) {
            throw new IllegalArgumentException("该路径无变更记录: " + path);
        }
        return dto;
    }

    /** 读取当前文件内容（打开/预览/编辑视图） */
    @GetMapping("/content")
    public Map<String, Object> content(@PathVariable String sessionId,
                                       @RequestParam String path) throws Exception {
        String content = fileService.readFile(sessionId, path);
        if (content == null) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        return Map.of("path", path, "content", content);
    }

    public record SaveContentRequest(String content) {
    }

    /** 保存文件内容（编辑写回工作空间；与模型写入同一变更记录入口） */
    @PutMapping("/content")
    public Map<String, Object> save(@PathVariable String sessionId,
                                    @RequestParam String path,
                                    @RequestBody SaveContentRequest req) throws Exception {
        fileService.saveContent(sessionId, path, req.content());
        return Map.of("ok", true);
    }
}
