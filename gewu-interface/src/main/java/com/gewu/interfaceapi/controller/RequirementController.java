package com.gewu.interfaceapi.controller;

import com.gewu.application.project.ProjectRepoService;
import com.gewu.application.requirement.RequirementFileService;
import com.gewu.application.requirement.RequirementService;
import com.gewu.application.requirement.dto.*;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import com.gewu.domain.requirement.RequirementFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
@Tag(name = "需求管理", description = "需求全生命周期管理 API")
public class RequirementController {

    private final RequirementService requirementService;
    private final RequirementFileService requirementFileService;
    private final ProjectRepoService projectRepoService;

    @GetMapping
    @Operation(summary = "分页查询需求列表", description = "支持按类型/优先级/状态/负责人/关键词筛选")
    public Result<PageResult<RequirementDTO>> listRequirements(RequirementQuery query) {
        return Result.success(requirementService.listRequirements(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取需求详情", description = "获取单个需求的完整信息")
    public Result<RequirementDTO> getRequirement(@PathVariable String id) {
        return Result.success(requirementService.getRequirement(id));
    }

    @PostMapping
    @Operation(summary = "创建需求", description = "创建新需求，自动生成需求编号")
    public Result<RequirementDTO> createRequirement(@RequestBody CreateRequirementCommand command) {
        return Result.success(requirementService.createRequirement(command));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新需求", description = "更新需求信息，仅草稿/可编辑状态允许修改")
    public Result<RequirementDTO> updateRequirement(@PathVariable String id,
                                                    @RequestBody UpdateRequirementCommand command) {
        return Result.success(requirementService.updateRequirement(id, command));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除需求", description = "软删除需求，数据保留")
    public Result<Void> deleteRequirement(@PathVariable String id) {
        requirementService.deleteRequirement(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新需求状态", description = "流转需求状态（如草稿→待评审→评审中→已通过等）")
    public Result<RequirementDTO> updateStatus(@PathVariable String id,
                                               @RequestBody UpdateRequirementStatusCommand command) {
        return Result.success(requirementService.updateStatus(id, command));
    }

    @PostMapping("/{id}/submit-review")
    @Operation(summary = "提交评审", description = "将需求提交评审流程")
    public Result<Void> submitReview(@PathVariable String id,
                                     @RequestBody SubmitReviewCommand command) {
        requirementService.submitReview(id, command);
        return Result.success();
    }

    @GetMapping("/{id}/reviews")
    @Operation(summary = "获取评审记录", description = "获取需求的所有评审记录")
    public Result<List<RequirementReviewDTO>> getReviews(@PathVariable String id) {
        return Result.success(requirementService.getReviews(id));
    }

    @PostMapping("/{id}/reviews")
    @Operation(summary = "提交评审意见", description = "评审人提交评审意见（通过/驳回）")
    public Result<Void> submitReviewOpinion(@PathVariable String id,
                                            @RequestBody SubmitReviewOpinionCommand command) {
        requirementService.submitReviewOpinion(id, command);
        return Result.success();
    }

    @GetMapping("/{id}/tasks")
    @Operation(summary = "获取需求任务列表", description = "获取需求关联的所有任务")
    public Result<List<RequirementTaskDTO>> getTasks(@PathVariable String id) {
        return Result.success(requirementService.getTasks(id));
    }

    @PostMapping("/{id}/tasks")
    @Operation(summary = "创建任务", description = "为需求创建开发任务")
    public Result<RequirementTaskDTO> createTask(@PathVariable String id,
                                                 @RequestBody CreateTaskCommand command) {
        return Result.success(requirementService.createTask(id, command));
    }

    @PutMapping("/tasks/{taskId}")
    @Operation(summary = "更新任务", description = "更新任务信息或状态")
    public Result<RequirementTaskDTO> updateTask(@PathVariable String taskId,
                                                 @RequestBody UpdateTaskCommand command) {
        return Result.success(requirementService.updateTask(taskId, command));
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "删除任务", description = "软删除任务")
    public Result<Void> deleteTask(@PathVariable String taskId) {
        requirementService.deleteTask(taskId);
        return Result.success();
    }

    @GetMapping("/{id}/comments")
    @Operation(summary = "获取评论列表", description = "获取需求的评论列表")
    public Result<List<RequirementCommentDTO>> getComments(@PathVariable String id) {
        return Result.success(requirementService.getComments(id));
    }

    @PostMapping("/{id}/comments")
    @Operation(summary = "添加评论", description = "对需求发表评论")
    public Result<RequirementCommentDTO> createComment(@PathVariable String id,
                                                       @RequestBody CreateCommentCommand command) {
        return Result.success(requirementService.createComment(id, command));
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "删除评论", description = "删除评论")
    public Result<Void> deleteComment(@PathVariable String commentId) {
        requirementService.deleteComment(commentId);
        return Result.success();
    }

    @GetMapping("/stats")
    @Operation(summary = "需求统计", description = "获取需求统计数据（按状态/类型/优先级分组）")
    public Result<RequirementStatsDTO> getStats() {
        return Result.success(requirementService.getStats());
    }

    // ==================== 需求文件空间 ====================

    @PostMapping("/{id}/files")
    @Operation(summary = "上传需求文件", description = "按分类(docs/tests/reports)上传到需求文件空间")
    public Result<RequirementFile> uploadFile(@PathVariable String id,
                                               @RequestParam String category,
                                               @RequestParam("file") MultipartFile file) {
        return Result.success(requirementFileService.uploadFile(id, category, file));
    }

    @GetMapping("/{id}/files")
    @Operation(summary = "需求文件列表", description = "按分类查询需求文件")
    public Result<List<RequirementFile>> listFiles(@PathVariable String id,
                                                    @RequestParam(required = false) String category) {
        return Result.success(requirementFileService.listFiles(id, category));
    }

    @GetMapping("/{id}/files/{fileId}/content")
    @Operation(summary = "读取文件内容")
    public ResponseEntity<String> readFile(@PathVariable String id, @PathVariable String fileId) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(requirementFileService.getFileContent(id, fileId));
    }

    @DeleteMapping("/{id}/files/{fileId}")
    @Operation(summary = "删除需求文件")
    public Result<Void> deleteFile(@PathVariable String id, @PathVariable String fileId) {
        requirementFileService.deleteFile(id, fileId);
        return Result.success();
    }

    @GetMapping("/{id}/files/{fileId}/download")
    @Operation(summary = "下载需求文件")
    public Result<String> downloadFile(@PathVariable String id, @PathVariable String fileId) {
        return Result.success(requirementFileService.getDownloadUrl(id, fileId));
    }

    // ==================== 需求开发分支 ====================

    @PostMapping("/{id}/branch")
    @Operation(summary = "创建开发分支", description = "为需求创建 feature/{requirementCode} 分支")
    public Result<RequirementDTO> createBranch(@PathVariable String id) {
        var req = requirementService.getRequirement(id);
        projectRepoService.createRequirementBranch(req.getProjectId(), id);
        return Result.success(requirementService.getRequirement(id));
    }
}
