package com.gewu.interfaceapi.controller;

import com.gewu.application.session.SessionFileWorkspaceService;
import com.gewu.application.session.SessionService;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话文件变更接口（S9 F3）——右侧文件编辑面板的数据源。
 * <p>变更列表（含 +N/-N）/ 差异审查（before 快照 vs 当前内容）/
 * 当前内容读取与编辑保存（写回会话工作空间沙箱）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/file-changes")
@RequiredArgsConstructor
@Tag(name = "会话文件变更", description = "会话编辑文件列表、差异审查与内容读写")
public class SessionFileChangeController {

    private final SessionFileWorkspaceService fileWorkspaceService;
    private final SessionService sessionService;

    @GetMapping
    @Operation(summary = "变更文件列表", description = "本次会话编辑的所有文件（含增删行数统计）")
    public Result<List<SessionFileWorkspaceService.FileChangeDTO>> list(
            @PathVariable String sessionId) {
        sessionService.getSession(sessionId); // 成员/公开可见性校验
        return Result.success(fileWorkspaceService.listChanges(sessionId));
    }

    @GetMapping("/diff")
    @Operation(summary = "变更差异", description = "before 快照与当前内容的差异（审查视图）")
    public Result<SessionFileWorkspaceService.FileDiffDTO> diff(
            @PathVariable String sessionId, @RequestParam String path) {
        sessionService.getSession(sessionId);
        return Result.success(fileWorkspaceService.diff(sessionId, path));
    }

    @GetMapping("/content")
    @Operation(summary = "读取当前内容", description = "会话工作空间中的文件内容（打开/编辑视图）")
    public Result<String> content(
            @PathVariable String sessionId, @RequestParam String path) {
        sessionService.getSession(sessionId);
        return Result.success(fileWorkspaceService.readCurrentContent(sessionId, path));
    }

    @PutMapping("/content")
    @Operation(summary = "保存文件内容", description = "编辑后写回会话工作空间（计入会话变更记录）")
    public Result<Void> save(
            @PathVariable String sessionId, @RequestParam String path,
            @RequestBody Map<String, String> body) {
        sessionService.getSession(sessionId);
        fileWorkspaceService.saveContent(sessionId, path, body.get("content"));
        return Result.success();
    }
}
