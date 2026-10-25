package com.gewu.interfaceapi.controller;

import com.gewu.application.project.ProjectRepoService;
import com.gewu.application.workspace.DevWorkspaceService;
import com.gewu.application.workspace.dto.DevWorkspaceDTO;
import com.gewu.application.workspace.dto.GitProjectDTO;
import com.gewu.common.result.Result;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.dto.sandbox.SandboxDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 开发工作空间接口 - 面向 DEV/TEST 角色的云端开发环境.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev-workspaces")
@RequiredArgsConstructor
@Tag(name = "开发工作空间", description = "Git 项目管理、文件编辑、编译运行")
public class DevWorkspaceController {

    private final DevWorkspaceService devWorkspaceService;
    private final ProjectRepoService projectRepoService;

    // ==================== 沙箱管理 ====================

    @GetMapping
    @Operation(summary = "获取开发工作空间状态")
    public Result<DevWorkspaceDTO> getDevWorkspace() {
        return Result.success(devWorkspaceService.getDevWorkspace());
    }

    @PostMapping("/sandbox/start")
    @Operation(summary = "启动开发沙箱", description = "创建或恢复开发沙箱（含 git 工具 + 网络）")
    public Result<SandboxDTO> startSandbox() {
        return Result.success(devWorkspaceService.startDevSandbox());
    }

    @PostMapping("/sandbox/stop")
    @Operation(summary = "停止开发沙箱", description = "停止但保留 Volume 数据")
    public Result<Void> stopSandbox() {
        devWorkspaceService.stopDevSandbox();
        return Result.success();
    }

    // ==================== 文件操作 ====================

    @GetMapping("/files")
    @Operation(summary = "列出目录内容", description = "通过 exec ls 列出 /workspace 下的文件")
    public Result<String> listFiles(@RequestParam(required = false) String path) {
        return Result.success(devWorkspaceService.listFiles(path));
    }

    @GetMapping("/files/content")
    @Operation(summary = "读取文件内容")
    public ResponseEntity<String> readFile(@RequestParam String path) {
        String content = devWorkspaceService.readFile(path);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(content);
    }

    @PutMapping("/files/content")
    @Operation(summary = "写入文件内容", description = "通过 Docker cp 写入（绕过命令限制）")
    public Result<Void> writeFile(@RequestBody WriteFileRequest request) {
        devWorkspaceService.writeFile(request.getPath(), request.getContent());
        return Result.success();
    }

    @PostMapping("/files/upload")
    @Operation(summary = "上传文件到工作空间")
    public Result<Void> uploadFile(
            @RequestParam("path") String path,
            @RequestParam("file") MultipartFile file) throws IOException {
        devWorkspaceService.uploadFile(path, file);
        return Result.success();
    }

    @GetMapping("/files/download")
    @Operation(summary = "下载文件")
    public ResponseEntity<byte[]> downloadFile(@RequestParam String path) {
        byte[] data = devWorkspaceService.downloadFile(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + getFileName(path) + "\"")
                .body(data);
    }

    @DeleteMapping("/files")
    @Operation(summary = "删除文件/目录")
    public Result<Void> deleteFile(@RequestParam String path) {
        devWorkspaceService.deleteFile(path);
        return Result.success();
    }

    @GetMapping("/files/entries")
    @Operation(summary = "结构化目录列表", description = "返回名称/类型/大小/修改时间条目（文件树数据源）")
    public Result<List<com.gewu.application.workspace.dto.WorkspaceFileEntry>> listEntries(
            @RequestParam(required = false, defaultValue = "") String path) {
        return Result.success(devWorkspaceService.listEntries(path));
    }

    @PostMapping("/exec")
    @Operation(summary = "执行命令", description = "在开发沙箱中执行 shell 命令（宽松校验）")
    public Result<ExecCommandResponse> execCommand(@RequestBody ExecRequest request) {
        return Result.success(devWorkspaceService.execCommand(request.getCommand(), request.getTimeout()));
    }

    // ==================== Git 项目管理 ====================
    // 项目来源：我的项目分配（listProjects 按成员行过滤）；克隆/拉取统一走
    // ProjectRepoService 按项目配置的 Git 地址与凭证执行，用户侧无手动克隆入口。

    @GetMapping("/projects")
    @Operation(summary = "项目列表", description = "我的项目中分配给当前用户的项目")
    public Result<List<GitProjectDTO>> listProjects() {
        return Result.success(devWorkspaceService.listProjects());
    }

    @PostMapping("/projects/{projectId}/pull")
    @Operation(summary = "Git pull / 自动克隆", description = "拉取项目仓库最新代码；仓库未就绪时按项目配置的 Git 地址与凭证自动克隆")
    public Result<Void> gitPull(@PathVariable String projectId) {
        projectRepoService.gitPull(projectId);
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/push")
    @Operation(summary = "Git commit + push")
    public Result<String> gitPush(@PathVariable String projectId, @RequestBody Map<String, String> request) {
        return Result.success(devWorkspaceService.gitCommitPush(projectId, request.get("message")));
    }

    @PostMapping("/projects/{projectId}/build")
    @Operation(summary = "构建项目", description = "执行构建命令（如 mvn compile）")
    public Result<ExecCommandResponse> build(
            @PathVariable String projectId, @RequestBody Map<String, String> request) {
        return Result.success(devWorkspaceService.build(projectId, request.get("command")));
    }

    @PostMapping("/projects/{projectId}/run")
    @Operation(summary = "运行项目", description = "执行运行命令（如 java -jar）")
    public Result<ExecCommandResponse> run(
            @PathVariable String projectId, @RequestBody Map<String, String> request) {
        return Result.success(devWorkspaceService.run(projectId, request.get("command")));
    }

    // ==================== 辅助 ====================

    private String getFileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @lombok.Data
    public static class WriteFileRequest {
        private String path;
        private String content;
    }

    @lombok.Data
    public static class ExecRequest {
        private String command;
        private Integer timeout;
    }
}
