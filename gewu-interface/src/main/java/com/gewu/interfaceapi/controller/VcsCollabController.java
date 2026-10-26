package com.gewu.interfaceapi.controller;

import com.gewu.application.vcs.VcsCollabService;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 托管平台协作接口（适配层门面，托管商无关）。
 * 能力前提：项目配置 token 型仓库凭证 + repoUrl 平台可识别；
 * 不满足时报错降级提示（git 协议层功能不受影响）。
 */
@RestController
@RequestMapping("/api/v1/vcs/{projectId}")
@RequiredArgsConstructor
@Tag(name = "托管平台协作", description = "MR/评审/CI/Issue 通用适配（主流程）")
public class VcsCollabController {

    private final VcsCollabService vcsCollabService;

    @GetMapping("/mrs")
    @Operation(summary = "合并请求列表", description = "state: open/all/merged/closed")
    public Result<List<MergeRequestDTO>> listMRs(@PathVariable String projectId,
                                                 @RequestParam(defaultValue = "open") String state) {
        return Result.success(vcsCollabService.listMRs(projectId, state));
    }

    @PostMapping("/mrs")
    @Operation(summary = "发起合并请求")
    public Result<MergeRequestDTO> createMR(@PathVariable String projectId, @RequestBody MRCreateRequest request) {
        return Result.success(vcsCollabService.createMR(projectId,
                request.getSourceBranch(), request.getTargetBranch(), request.getTitle(), request.getDescription()));
    }

    @GetMapping("/mrs/{number}")
    @Operation(summary = "合并请求详情", description = "含源分支 HEAD SHA（CI 查询用）")
    public Result<MergeRequestDTO> getMR(@PathVariable String projectId, @PathVariable long number) {
        return Result.success(vcsCollabService.getMR(projectId, number));
    }

    @GetMapping("/ci")
    @Operation(summary = "CI 状态聚合", description = "按分支名或 SHA")
    public Result<CiStatusDTO> ciOf(@PathVariable String projectId, @RequestParam String ref) {
        return Result.success(vcsCollabService.ciOf(projectId, ref));
    }

    @GetMapping("/issues")
    @Operation(summary = "Issue 列表", description = "state: open/closed/all")
    public Result<List<IssueDTO>> listIssues(@PathVariable String projectId,
                                             @RequestParam(defaultValue = "open") String state) {
        return Result.success(vcsCollabService.listIssues(projectId, state));
    }

    @GetMapping("/issues/{number}")
    @Operation(summary = "Issue 详情", description = "含评论（时间升序）")
    public Result<IssueDTO> getIssue(@PathVariable String projectId, @PathVariable long number) {
        return Result.success(vcsCollabService.getIssue(projectId, number));
    }

    @PostMapping("/issues/{number}/comments")
    @Operation(summary = "Issue 评论", description = "P2 写操作（按平台原生评论通道）")
    public Result<Void> commentIssue(@PathVariable String projectId, @PathVariable long number, @RequestBody CommentRequest request) {
        vcsCollabService.commentIssue(projectId, number, request.getBody());
        return Result.success();
    }

    @GetMapping("/releases")
    @Operation(summary = "Release 列表", description = "基于 tag 的平台发布记录（按创建时间倒序）")
    public Result<List<com.gewu.application.vcs.dto.ReleaseDTO>> listReleases(@PathVariable String projectId) {
        return Result.success(vcsCollabService.listReleases(projectId));
    }

    @PostMapping("/releases")
    @Operation(summary = "创建 Release", description = "基于已推送的 tag 创建（P2；产物附件上传留 P3）")
    public Result<com.gewu.application.vcs.dto.ReleaseDTO> createRelease(@PathVariable String projectId, @RequestBody ReleaseCreateRequest request) {
        return Result.success(vcsCollabService.createRelease(projectId,
                request.getTagName(), request.getName(), request.getNotes(),
                Boolean.TRUE.equals(request.getPrerelease())));
    }

    @lombok.Data
    public static class MRCreateRequest {
        private String sourceBranch;
        private String targetBranch;
        private String title;
        private String description;
    }

    @lombok.Data
    public static class CommentRequest {
        private String body;
    }

    @lombok.Data
    public static class ReleaseCreateRequest {
        private String tagName;
        private String name;
        private String notes;
        private Boolean prerelease;
    }
}
