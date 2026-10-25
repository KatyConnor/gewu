package com.gewu.interfaceapi.controller;

import com.gewu.application.project.ProjectRepoService;
import com.gewu.application.workspace.DevWorkspaceService;
import com.gewu.application.workspace.dto.DevWorkspaceDTO;
import com.gewu.application.workspace.dto.GitBranchesDTO;
import com.gewu.application.workspace.dto.GitLogEntryDTO;
import com.gewu.application.workspace.dto.GitOpCommand;
import com.gewu.application.workspace.dto.GitProjectDTO;
import com.gewu.application.workspace.dto.GitStashDTO;
import com.gewu.application.workspace.dto.GitStatusDTO;
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
    @Operation(summary = "Git push", description = "推送当前分支到远程（-u 建立跟踪）；提交请先走 commit")
    public Result<String> gitPush(@PathVariable String projectId) {
        return Result.success(devWorkspaceService.gitPush(projectId));
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

    // ==================== Git 常规操作（IDEA 式 Git 菜单） ====================

    @GetMapping("/projects/{projectId}/git/status")
    @Operation(summary = "工作区状态", description = "当前分支/上游/领先落后/变更清单（提交与回滚对话框数据源）")
    public Result<GitStatusDTO> gitStatus(@PathVariable String projectId) {
        return Result.success(devWorkspaceService.gitStatus(projectId));
    }

    @GetMapping("/projects/{projectId}/git/log")
    @Operation(summary = "提交历史")
    public Result<List<GitLogEntryDTO>> gitLog(@PathVariable String projectId,
                                               @RequestParam(required = false) Integer limit) {
        return Result.success(devWorkspaceService.gitLog(projectId, limit));
    }

    @GetMapping("/projects/{projectId}/git/branches")
    @Operation(summary = "分支列表", description = "本地分支（含当前）与远程跟踪分支")
    public Result<GitBranchesDTO> gitBranches(@PathVariable String projectId) {
        return Result.success(devWorkspaceService.gitBranches(projectId));
    }

    @GetMapping("/projects/{projectId}/git/stashes")
    @Operation(summary = "stash 列表")
    public Result<List<GitStashDTO>> gitStashList(@PathVariable String projectId) {
        return Result.success(devWorkspaceService.gitStashList(projectId));
    }

    @GetMapping("/projects/{projectId}/git/diff")
    @Operation(summary = "单文件差异", description = "工作区与 HEAD 的差异（未跟踪文件为空串）")
    public Result<String> gitDiff(@PathVariable String projectId, @RequestParam String path) {
        return Result.success(devWorkspaceService.gitFileDiff(projectId, path));
    }

    @PostMapping("/projects/{projectId}/git/commit")
    @Operation(summary = "提交", description = "勾选文件 add 后 commit（无勾选提交已暂存内容），支持 amend")
    public Result<Void> gitCommit(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitCommit(projectId, command);
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/fetch")
    @Operation(summary = "获取", description = "fetch --prune")
    public Result<Void> gitFetch(@PathVariable String projectId) {
        devWorkspaceService.gitFetch(projectId);
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/branch")
    @Operation(summary = "创建分支", description = "可选创建后立即检出")
    public Result<Void> gitCreateBranch(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitCreateBranch(projectId, command.getName(), command.getCheckout());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/checkout")
    @Operation(summary = "切换分支")
    public Result<Void> gitCheckout(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitCheckout(projectId, command.getBranch());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/branch-delete")
    @Operation(summary = "删除本地分支", description = "-d 安全删除：未合入分支拒绝删除；当前分支不可删")
    public Result<Void> gitDeleteBranch(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitDeleteBranch(projectId, command.getName());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/merge")
    @Operation(summary = "合并分支", description = "合并指定分支到当前分支（冲突时错误透出）")
    public Result<Void> gitMerge(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitMerge(projectId, command.getBranch());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/stash")
    @Operation(summary = "暂存更改", description = "stash push -u（含未跟踪文件）")
    public Result<Void> gitStash(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitStash(projectId, command.getMessage());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/stash-pop")
    @Operation(summary = "弹出 stash", description = "stash pop 应用到工作区并出栈")
    public Result<Void> gitStashPop(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitStashPop(projectId, command.getIndex());
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/git/rollback")
    @Operation(summary = "回滚更改", description = "跟踪文件还原到 HEAD；未跟踪/新增文件从磁盘清除")
    public Result<Void> gitRollback(@PathVariable String projectId, @RequestBody GitOpCommand command) {
        devWorkspaceService.gitRollback(projectId, command.getPaths(), command.getUntrackedPaths());
        return Result.success();
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
