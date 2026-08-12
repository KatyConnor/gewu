package com.gewu.interfaceapi.controller;

import com.gewu.application.project.PhaseDocumentService;
import com.gewu.application.project.dto.*;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "项目文档管理", description = "阶段文档的创建、编辑、版本管理和审核")
public class ProjectDocumentController {

    private final PhaseDocumentService documentService;

    // ==================== 文档 CRUD ====================

    @GetMapping("/documents/{documentId}")
    @Operation(summary = "获取文档详情", description = "获取文档元信息和审核状态")
    public Result<PhaseDocumentDTO> getDocument(@PathVariable String documentId) {
        return Result.success(documentService.getDocument(documentId));
    }

    @GetMapping("/documents/{documentId}/content")
    @Operation(summary = "获取文档内容", description = "获取文档当前版本的 Markdown 原文内容")
    public Result<String> getDocumentContent(@PathVariable String documentId) {
        return Result.success(documentService.getDocumentContent(documentId));
    }

    @PutMapping("/documents/{documentId}/content")
    @Operation(summary = "编辑文档", description = "保存文档修改，生成新版本，状态变更为待审核")
    public Result<PhaseDocumentDTO> updateContent(@PathVariable String documentId,
                                                  @RequestBody UpdateDocumentContentCommand command) {
        return Result.success(documentService.updateContent(documentId, command));
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "删除文档", description = "删除文档及所有历史版本")
    public Result<Void> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/phases/{phaseCode}/docs")
    @Operation(summary = "上传文件文档", description = "上传文件到指定项目阶段，自动创建文档记录")
    public Result<PhaseDocumentDTO> uploadFile(@PathVariable String projectId,
                                               @PathVariable String phaseCode,
                                               @RequestParam("file") MultipartFile file) {
        return Result.success(documentService.uploadFile(projectId, phaseCode, file));
    }

    @PostMapping("/projects/{projectId}/phases/{phaseCode}/docs/md")
    @Operation(summary = "创建 Markdown 文档", description = "在指定阶段新建 Markdown 文档")
    public Result<PhaseDocumentDTO> createMarkdownDoc(@PathVariable String projectId,
                                                      @PathVariable String phaseCode,
                                                      @RequestBody CreateDocumentCommand command) {
        return Result.success(documentService.createMarkdownDoc(projectId, phaseCode, command));
    }

    @GetMapping("/projects/{projectId}/phases/{phaseCode}/docs")
    @Operation(summary = "阶段文档列表", description = "获取指定项目阶段的所有文档")
    public Result<List<PhaseDocumentDTO>> listDocuments(@PathVariable String projectId,
                                                        @PathVariable String phaseCode) {
        return Result.success(documentService.listDocuments(projectId, phaseCode));
    }

    // ==================== 版本管理 ====================

    @GetMapping("/documents/{documentId}/versions")
    @Operation(summary = "版本历史", description = "获取文档的所有历史版本")
    public Result<List<DocumentVersionDTO>> getVersions(@PathVariable String documentId) {
        return Result.success(documentService.getVersions(documentId));
    }

    @GetMapping("/documents/{documentId}/versions/{versionNo}")
    @Operation(summary = "获取指定版本内容", description = "获取文档某个历史版本的内容")
    public Result<String> getVersionContent(@PathVariable String documentId,
                                            @PathVariable int versionNo) {
        return Result.success(documentService.getVersionContent(documentId, versionNo));
    }

    // ==================== 审核管理 ====================

    @PostMapping("/documents/{documentId}/submit-review")
    @Operation(summary = "提交审核", description = "将文档提交给 Agent 审核，状态变更为审核中")
    public Result<Void> submitForReview(@PathVariable String documentId) {
        documentService.submitForReview(documentId);
        return Result.success();
    }

    @PostMapping("/documents/{documentId}/agent-review/approve")
    @Operation(summary = "审核通过", description = "Agent 审核通过文档，文档不可再编辑")
    public Result<Void> approveReview(@PathVariable String documentId,
                                      @RequestParam String agentId,
                                      @RequestParam(required = false) String comment) {
        documentService.approveReview(documentId, agentId, comment);
        return Result.success();
    }

    @PostMapping("/documents/{documentId}/agent-review/reject")
    @Operation(summary = "审核驳回", description = "Agent 驳回文档，解锁允许修改后重新提交")
    public Result<Void> rejectReview(@PathVariable String documentId,
                                     @RequestParam String agentId,
                                     @RequestParam String comment) {
        documentService.rejectReview(documentId, agentId, comment);
        return Result.success();
    }
}
