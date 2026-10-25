package com.gewu.admin.controller;

import com.gewu.admin.dto.requirement.AdminRequirementCreateCommand;
import com.gewu.admin.dto.requirement.AdminRequirementDTO;
import com.gewu.admin.dto.requirement.AdminRequirementStatusCommand;
import com.gewu.admin.dto.requirement.AdminRequirementUpdateCommand;
import com.gewu.admin.service.AdminRequirementService;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端需求接口（需求管理迁移管理端：后台为需求唯一创建/编辑/分派入口）.
 * <p>分派铁律：负责人必须是需求绑定项目的 project_member 成员（服务层强校验）。
 * 用户侧（gewu-interface）同路径收敛为只读"我的需求"+评论协作。
 */
@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('requirement:manage')")
@Tag(name = "需求管理（管理端）", description = "需求创建、编辑、删除、状态流转与项目内分派")
public class AdminRequirementController {

    private final AdminRequirementService adminRequirementService;

    @GetMapping
    @Operation(summary = "需求列表", description = "分页查询，keyword 模糊匹配标题/编号")
    public Result<PageResult<AdminRequirementDTO>> listRequirements(@Valid PageQuery query,
                                                                    @RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) String type,
                                                                    @RequestParam(required = false) Integer priority,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String projectId) {
        return Result.success(adminRequirementService.listRequirements(query, keyword, type, priority, status, projectId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "需求详情")
    public Result<AdminRequirementDTO> getRequirement(@PathVariable String id) {
        return Result.success(adminRequirementService.getRequirement(id));
    }

    @PostMapping
    @Operation(summary = "创建需求", description = "创建需求（必须绑定项目；分派对象必须是该项目成员）")
    public Result<AdminRequirementDTO> createRequirement(@Valid @RequestBody AdminRequirementCreateCommand command) {
        return Result.success(adminRequirementService.createRequirement(command));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新需求", description = "基础字段与分派（换绑定项目后重新校验负责人成员身份）")
    public Result<AdminRequirementDTO> updateRequirement(@PathVariable String id,
                                                         @Valid @RequestBody AdminRequirementUpdateCommand command) {
        return Result.success(adminRequirementService.updateRequirement(id, command));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "状态流转", description = "需求生命周期状态变更（终态不可变更）")
    public Result<AdminRequirementDTO> updateStatus(@PathVariable String id,
                                                    @RequestBody AdminRequirementStatusCommand command) {
        return Result.success(adminRequirementService.updateStatus(id, command));
    }

    @PostMapping("/{id}/submit-review")
    @Operation(summary = "提交评审", description = "草稿/待评审状态的需求提交评审")
    public Result<Void> submitReview(@PathVariable String id,
                                     @RequestBody AdminRequirementStatusCommand command) {
        adminRequirementService.submitReview(id, command.getReviewType());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除需求", description = "逻辑删除需求与需求文件行（MinIO 附件对象清理暂不在管理端执行）")
    public Result<Void> deleteRequirement(@PathVariable String id) {
        adminRequirementService.deleteRequirement(id);
        return Result.success();
    }
}
