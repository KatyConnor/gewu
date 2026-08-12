package com.gewu.interfaceapi.controller;

import com.gewu.application.workspace.WorkspaceService;
import com.gewu.application.workspace.dto.CreateDirCommand;
import com.gewu.application.workspace.dto.FileNodeDTO;
import com.gewu.application.workspace.dto.RenameFileCommand;
import com.gewu.application.workspace.dto.WorkspaceDTO;
import com.gewu.common.result.Result;
import com.gewu.common.dto.sandbox.SandboxDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 用户工作空间接口 - 文件管理与沙箱集成.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
@Tag(name = "工作空间", description = "用户工作空间文件管理与开发环境")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户工作空间", description = "首次访问自动创建")
    public Result<WorkspaceDTO> getMyWorkspace() {
        return Result.success(workspaceService.getOrCreateMyWorkspace());
    }

    @GetMapping("/files")
    @Operation(summary = "文件树列表", description = "按父目录列出，不传 parentId 查根目录")
    public Result<List<FileNodeDTO>> listFiles(
            @RequestParam(required = false) String parentId) {
        return Result.success(workspaceService.listFiles(parentId));
    }

    @PostMapping("/files/dirs")
    @Operation(summary = "创建目录")
    public Result<FileNodeDTO> createDirectory(@Valid @RequestBody CreateDirCommand command) {
        return Result.success(workspaceService.createDirectory(command));
    }

    @PostMapping("/files/upload")
    @Operation(summary = "上传文件")
    public Result<FileNodeDTO> uploadFile(
            @RequestParam(required = false) String parentId,
            @RequestParam("file") MultipartFile file) {
        return Result.success(workspaceService.uploadFile(file, parentId));
    }

    @GetMapping("/files/{fileId}/content")
    @Operation(summary = "读取文件内容")
    public ResponseEntity<String> getFileContent(@PathVariable String fileId) {
        String content = workspaceService.getFileContent(fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(content);
    }

    @PutMapping("/files/{fileId}/content")
    @Operation(summary = "保存文件内容", description = "覆盖写，版本号 +1")
    public Result<FileNodeDTO> saveFileContent(
            @PathVariable String fileId,
            @RequestBody String content) {
        return Result.success(workspaceService.saveFileContent(fileId, content));
    }

    @PutMapping("/files/{fileId}")
    @Operation(summary = "重命名/移动文件")
    public Result<FileNodeDTO> renameFile(
            @PathVariable String fileId,
            @Valid @RequestBody RenameFileCommand command) {
        return Result.success(workspaceService.renameFile(fileId, command));
    }

    @DeleteMapping("/files/{fileId}")
    @Operation(summary = "删除文件/目录", description = "目录递归删除")
    public Result<Void> deleteFile(@PathVariable String fileId) {
        workspaceService.deleteFile(fileId);
        return Result.success();
    }

    @GetMapping("/files/{fileId}/download")
    @Operation(summary = "获取下载链接", description = "返回 MinIO 预签名 URL（7天有效）")
    public Result<Map<String, String>> downloadFile(@PathVariable String fileId) {
        String url = workspaceService.getDownloadUrl(fileId);
        return Result.success(Map.of("downloadUrl", url != null ? url : ""));
    }

    @PostMapping("/sandboxes")
    @Operation(summary = "创建工作空间沙箱", description = "挂载用户工作空间到 /workspace，支持持久化源码")
    public Result<SandboxDTO> createSandbox(@RequestBody Map<String, String> request) {
        String template = request.get("template");
        String sandboxName = request.get("sandboxName");
        return Result.success(workspaceService.createWorkspaceSandbox(template, sandboxName));
    }
}
