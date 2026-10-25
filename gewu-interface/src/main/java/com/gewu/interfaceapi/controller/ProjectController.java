package com.gewu.interfaceapi.controller;

import com.gewu.application.project.ProjectPhaseService;
import com.gewu.application.project.ProjectRepoService;
import com.gewu.application.project.ProjectService;
import com.gewu.application.project.dto.ProjectDTO;
import com.gewu.application.project.dto.ProjectMemberDTO;
import com.gewu.application.project.dto.ProjectPhaseDTO;
import com.gewu.application.project.dto.ProjectQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目接口 — 用户侧只读查询与项目内工作流.
 *
 * <p>项目管理（创建/编辑/删除/团队分发）已迁移管理端（gewu-admin-server，
 * project:manage 权限门内）；本控制器仅保留"我的项目"查询与项目内工作流
 * （阶段流转/仓库操作）。数据可见性由 project_member 成员行驱动：
 * 管理端创建项目时把技术负责人/团队成员落成员行，用户登录后 /my 即见分发项目。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "项目管理", description = "我的项目查询与项目内工作流")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectPhaseService projectPhaseService;
    private final ProjectRepoService projectRepoService;

    // ==================== 项目查询 ====================

    @GetMapping("/{projectId}")
    @Operation(summary = "获取项目详情", description = "根据项目ID获取项目详细信息（仅项目成员可见）")
    public Result<ProjectDTO> getProject(@PathVariable String projectId) {
        return Result.success(projectService.getProject(projectId));
    }

    @GetMapping("/my")
    @Operation(summary = "我的项目", description = "分页查询当前用户参与的项目（管理端分发的技术负责人/团队成员），支持搜索")
    public Result<PageResult<ProjectDTO>> listMyProjects(@Valid ProjectQuery query) {
        return Result.success(projectService.listMyProjects(query));
    }

    @GetMapping("/{projectId}/members")
    @Operation(summary = "项目成员列表", description = "获取项目所有成员")
    public Result<List<ProjectMemberDTO>> getProjectMembers(@PathVariable String projectId) {
        return Result.success(projectService.getProjectMembers(projectId));
    }

    // ==================== 阶段管理 ====================

    @GetMapping("/{projectId}/phases")
    @Operation(summary = "项目阶段列表", description = "获取项目的全部 17 个生命周期阶段及状态")
    public Result<List<ProjectPhaseDTO>> getPhases(@PathVariable String projectId) {
        return Result.success(projectPhaseService.getPhases(projectId));
    }

    @PutMapping("/{projectId}/phases/{phaseCode}/start")
    @Operation(summary = "开始阶段", description = "将指定阶段状态变更为进行中")
    public Result<Void> startPhase(@PathVariable String projectId, @PathVariable String phaseCode) {
        projectPhaseService.startPhase(projectId, phaseCode);
        return Result.success();
    }

    @PutMapping("/{projectId}/phases/{phaseCode}/complete")
    @Operation(summary = "完成阶段", description = "将指定阶段状态变更为已完成（需已上传文档）")
    public Result<Void> completePhase(@PathVariable String projectId, @PathVariable String phaseCode) {
        projectPhaseService.completePhase(projectId, phaseCode);
        return Result.success();
    }

    @PutMapping("/{projectId}/phases/{phaseCode}/revert")
    @Operation(summary = "回退阶段", description = "回退指定阶段到进行中（仅前 7 个阶段可回退）")
    public Result<Void> revertPhase(@PathVariable String projectId, @PathVariable String phaseCode) {
        projectPhaseService.revertPhase(projectId, phaseCode);
        return Result.success();
    }

    // ==================== 项目仓库管理 ====================

    @PostMapping("/{projectId}/repo/clone")
    @Operation(summary = "克隆项目仓库", description = "将 Git 仓库克隆到开发沙箱 /workspace/projects/{projectId}/repo/")
    public Result<ProjectDTO> cloneRepo(@PathVariable String projectId,
                                        @RequestParam String repoUrl,
                                        @RequestParam(required = false) String branch) {
        return Result.success(projectService.toDTO(projectRepoService.cloneProjectRepo(projectId, repoUrl, branch)));
    }

    @PostMapping("/{projectId}/repo/pull")
    @Operation(summary = "Git pull", description = "拉取项目仓库最新代码")
    public Result<ProjectDTO> pullRepo(@PathVariable String projectId) {
        return Result.success(projectService.toDTO(projectRepoService.gitPull(projectId)));
    }

    @GetMapping("/{projectId}/repo/status")
    @Operation(summary = "仓库状态", description = "获取项目仓库的 clone 状态和 HEAD commit")
    public Result<ProjectDTO> repoStatus(@PathVariable String projectId) {
        return Result.success(projectService.getProject(projectId));
    }
}
