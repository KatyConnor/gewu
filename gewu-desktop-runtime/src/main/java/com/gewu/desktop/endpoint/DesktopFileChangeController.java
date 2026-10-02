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

    public record UndoRequest(java.util.List<String> paths) {
    }

    /** 撤销回合变更（before 快照回写；paths 空=全部） */
    @org.springframework.web.bind.annotation.PostMapping("/undo")
    public java.util.List<java.util.Map<String, Object>> undo(@PathVariable String sessionId,
                                                              @org.springframework.web.bind.annotation.RequestBody(required = false) UndoRequest req) throws Exception {
        return fileService.undoTurn(sessionId, req == null ? null : req.paths());
    }

    /** 工作区 Git 分支（顶栏 chip） */
    @org.springframework.web.bind.annotation.GetMapping("/git-info")
    public java.util.Map<String, Object> gitInfo(@PathVariable String sessionId) {
        return java.util.Map.of("branch", fileService.gitBranch(sessionId));
    }

    /** 读取当前文件内容（data = 纯字符串，与平台契约一致——FileEditorPanel 直用）。
     * produces=JSON：String 返回值默认走 ISO-8859-1 文本转换器（中文响应损坏实测） */
    @GetMapping(value = "/content", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public com.gewu.common.result.Result<String> content(@PathVariable String sessionId,
                          @RequestParam String path) throws Exception {
        String content = fileService.readFile(sessionId, path);
        if (content == null) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        // 显式 Result<String>：绕开 String 返回值被 StringHttpMessageConverter
        // 以 ISO-8859-1 写出的缺陷（中文响应损坏实测）；data=纯字符串与平台契约一致
        return com.gewu.common.result.Result.success(content);
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
