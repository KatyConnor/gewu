package com.gewu.admin.controller;

import com.gewu.admin.dto.project.AdminProjectCreateCommand;
import com.gewu.admin.dto.project.AdminProjectDTO;
import com.gewu.admin.dto.project.AdminProjectUpdateCommand;
import com.gewu.admin.service.AdminProjectService;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端项目接口（项目管理迁移管理端：后台为项目唯一创建/编辑入口，团队分发即成员行落库）.
 * <p>用户侧（gewu-interface）同路径 /api/v1/projects 收敛为只读"我的项目"+工作流，
 * 管理端 CRUD 全部走本控制器（project:manage 权限门）。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('project:manage')")
@Tag(name = "项目管理（管理端）", description = "项目创建、编辑、删除与团队分发")
public class AdminProjectController {

    private final AdminProjectService adminProjectService;

    @GetMapping
    @Operation(summary = "项目列表", description = "分页查询，keyword 模糊匹配名称/编号/所属系统/技术负责人")
    public Result<PageResult<AdminProjectDTO>> listProjects(@Valid PageQuery query,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) Integer status) {
        return Result.success(adminProjectService.listProjects(query, keyword, status));
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "项目详情")
    public Result<AdminProjectDTO> getProject(@PathVariable String projectId) {
        return Result.success(adminProjectService.getProject(projectId));
    }

    @GetMapping("/{projectId}/members")
    @Operation(summary = "项目成员列表", description = "需求/任务分派选择器数据源：分派对象必须是项目成员")
    public Result<List<AdminProjectService.ProjectMemberView>> getProjectMembers(@PathVariable String projectId) {
        return Result.success(adminProjectService.getProjectMembers(projectId));
    }

    @PostMapping
    @Operation(summary = "创建项目", description = "创建项目并分发技术负责人/团队成员（落 project_member 行）")
    public Result<AdminProjectDTO> createProject(@Valid @RequestBody AdminProjectCreateCommand command) {
        return Result.success(adminProjectService.createProject(command));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "更新项目", description = "基础字段与团队整队重分配（techLeadUserId/memberUserIds 见命令语义）")
    public Result<AdminProjectDTO> updateProject(@PathVariable String projectId,
                                                 @Valid @RequestBody AdminProjectUpdateCommand command) {
        return Result.success(adminProjectService.updateProject(projectId, command));
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除项目", description = "逻辑删除项目与成员行（沙箱等产物级联清理暂不在管理端执行）")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        adminProjectService.deleteProject(projectId);
        return Result.success();
    }
}
