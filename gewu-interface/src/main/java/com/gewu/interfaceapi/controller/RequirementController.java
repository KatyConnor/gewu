package com.gewu.interfaceapi.controller;

import com.gewu.application.requirement.RequirementFileService;
import com.gewu.application.requirement.RequirementService;
import com.gewu.application.requirement.dto.CreateCommentCommand;
import com.gewu.application.requirement.dto.RequirementCommentDTO;
import com.gewu.application.requirement.dto.RequirementDTO;
import com.gewu.application.requirement.dto.RequirementQuery;
import com.gewu.application.requirement.dto.RequirementStatsDTO;
import com.gewu.application.requirement.dto.RequirementTaskDTO;
import com.gewu.application.requirement.dto.RequirementReviewDTO;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import com.gewu.domain.requirement.RequirementFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 需求接口 — 用户侧只读"我的需求"与协作评论.
 *
 * <p>需求管理（创建/编辑/删除/状态流转/评审提交/分派）已迁移管理端
 * （gewu-admin-server，requirement:manage 权限门内）；本控制器仅保留只读查询
 * 与评论协作。可见性铁律：仅分派给自己的需求（assignee=当前用户）可见，
 * 由管理端分派时校验"负责人必须是绑定项目成员"。
 */
@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
@Tag(name = "我的需求", description = "用户侧只读需求查询与评论协作")
public class RequirementController {

    private final RequirementService requirementService;
    private final RequirementFileService requirementFileService;

    // ==================== 我的需求 ====================

    @GetMapping("/my")
    @Operation(summary = "我的需求", description = "分页查询分派给当前用户的需求，支持按类型/优先级/状态/关键词筛选")
    public Result<PageResult<RequirementDTO>> listMyRequirements(RequirementQuery query) {
        return Result.success(requirementService.listMyRequirements(query));
    }

    @GetMapping("/stats")
    @Operation(summary = "我的需求统计", description = "按状态/类型/优先级分组统计分派给当前用户的需求")
    public Result<RequirementStatsDTO> getMyStats() {
        return Result.success(requirementService.getMyStats());
    }

    @GetMapping("/{id}")
    @Operation(summary = "需求详情", description = "仅分派给当前用户的需求可见")
    public Result<RequirementDTO> getRequirement(@PathVariable String id) {
        requirementService.requireVisible(id);
        return Result.success(requirementService.getRequirement(id));
    }

    // ==================== 协作记录（只读 + 评论参与） ====================

    @GetMapping("/{id}/reviews")
    @Operation(summary = "评审记录")
    public Result<List<RequirementReviewDTO>> getReviews(@PathVariable String id) {
        requirementService.requireVisible(id);
        return Result.success(requirementService.getReviews(id));
    }

    @GetMapping("/{id}/tasks")
    @Operation(summary = "任务列表")
    public Result<List<RequirementTaskDTO>> getTasks(@PathVariable String id) {
        requirementService.requireVisible(id);
        return Result.success(requirementService.getTasks(id));
    }

    @GetMapping("/{id}/comments")
    @Operation(summary = "评论列表")
    public Result<List<RequirementCommentDTO>> getComments(@PathVariable String id) {
        requirementService.requireVisible(id);
        return Result.success(requirementService.getComments(id));
    }

    @PostMapping("/{id}/comments")
    @Operation(summary = "添加评论", description = "需求协作参与：对分派给自己的需求发表评论")
    public Result<RequirementCommentDTO> createComment(@PathVariable String id,
                                                       @RequestBody CreateCommentCommand command) {
        requirementService.requireVisible(id);
        return Result.success(requirementService.createComment(id, command));
    }

    // ==================== 需求文件空间（只读） ====================

    @GetMapping("/{id}/files")
    @Operation(summary = "需求文件列表", description = "按分类查询需求文件")
    public Result<List<RequirementFile>> listFiles(@PathVariable String id,
                                                    @RequestParam(required = false) String category) {
        requirementService.requireVisible(id);
        return Result.success(requirementFileService.listFiles(id, category));
    }

    @GetMapping("/{id}/files/{fileId}/content")
    @Operation(summary = "读取文件内容")
    public ResponseEntity<String> readFile(@PathVariable String id, @PathVariable String fileId) {
        requirementService.requireVisible(id);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(requirementFileService.getFileContent(id, fileId));
    }

    @GetMapping("/{id}/files/{fileId}/download")
    @Operation(summary = "下载需求文件")
    public Result<String> downloadFile(@PathVariable String id, @PathVariable String fileId) {
        requirementService.requireVisible(id);
        return Result.success(requirementFileService.getDownloadUrl(id, fileId));
    }
}
