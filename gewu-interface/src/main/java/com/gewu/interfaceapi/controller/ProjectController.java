package com.gewu.interfaceapi.controller;

import com.gewu.application.project.ProjectMemberService;
import com.gewu.application.project.ProjectService;
import com.gewu.application.project.dto.AddMemberCommand;
import com.gewu.application.project.dto.CreateProjectCommand;
import com.gewu.application.project.dto.ProjectDTO;
import com.gewu.application.project.dto.ProjectMemberDTO;
import com.gewu.application.project.dto.UpdateProjectCommand;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目接口 — 项目创建、查询、更新、删除及成员管理.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "项目管理", description = "项目创建、查询、更新及成员管理")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;

    @PostMapping
    @Operation(summary = "创建项目", description = "创建新项目并将当前用户设为项目所有者")
    public Result<ProjectDTO> createProject(@Valid @RequestBody CreateProjectCommand command) {
        return Result.success(projectService.createProject(command));
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "获取项目详情", description = "根据项目ID获取项目详细信息")
    public Result<ProjectDTO> getProject(@PathVariable String projectId) {
        return Result.success(projectService.getProject(projectId));
    }

    @GetMapping
    @Operation(summary = "项目列表", description = "分页查询项目列表")
    public Result<PageResult<ProjectDTO>> listProjects(@Valid PageQuery query) {
        return Result.success(projectService.listProjects(query));
    }

    @GetMapping("/my")
    @Operation(summary = "我的项目", description = "分页查询当前用户参与的项目")
    public Result<PageResult<ProjectDTO>> listMyProjects(@Valid PageQuery query) {
        return Result.success(projectService.listMyProjects(query));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "更新项目", description = "更新项目信息，仅项目所有者可操作")
    public Result<ProjectDTO> updateProject(@PathVariable String projectId,
                                            @Valid @RequestBody UpdateProjectCommand command) {
        return Result.success(projectService.updateProject(projectId, command));
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除项目", description = "软删除项目，仅项目所有者可操作")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return Result.success();
    }

    @GetMapping("/{projectId}/members")
    @Operation(summary = "项目成员列表", description = "获取项目所有成员")
    public Result<List<ProjectMemberDTO>> getProjectMembers(@PathVariable String projectId) {
        return Result.success(projectService.getProjectMembers(projectId));
    }

    @PostMapping("/{projectId}/members")
    @Operation(summary = "添加项目成员", description = "向项目添加新成员")
    public Result<ProjectMemberDTO> addMember(@PathVariable String projectId,
                                              @Valid @RequestBody AddMemberCommand command) {
        return Result.success(projectMemberService.addMember(projectId, command));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @Operation(summary = "移除项目成员", description = "从项目中移除指定成员")
    public Result<Void> removeMember(@PathVariable String projectId, @PathVariable String userId) {
        projectMemberService.removeMember(projectId, userId);
        return Result.success();
    }

    @PutMapping("/{projectId}/members/{userId}")
    @Operation(summary = "更新成员角色", description = "更新项目成员的角色编码")
    public Result<Void> updateMemberRole(@PathVariable String projectId,
                                          @PathVariable String userId,
                                          @RequestParam String roleCode) {
        projectMemberService.updateMemberRole(projectId, userId, roleCode);
        return Result.success();
    }
}