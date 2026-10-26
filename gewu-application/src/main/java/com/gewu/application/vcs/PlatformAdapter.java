package com.gewu.application.vcs;

import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;

import java.util.List;

/**
 * 托管平台适配器（协作层主流程，P1 范围：MR 主流程 + CI 聚合 + Issue 只读）。
 * 实现按平台隔离（GitHub/GitLab/Gitee/Gitea），统一经 ProjectRef 访问。
 */
public interface PlatformAdapter {

    PlatformType platform();

    /** 合并请求列表（state: open/all/merged/closed，实现按平台归一） */
    List<MergeRequestDTO> listMRs(ProjectRef ref, String state);

    /** 发起合并请求 */
    MergeRequestDTO createMR(ProjectRef ref, String sourceBranch, String targetBranch, String title, String description);

    /** 合并请求详情（含 headSha 供 CI 查询） */
    MergeRequestDTO getMR(ProjectRef ref, long number);

    /** CI 状态聚合（分支名或 SHA） */
    CiStatusDTO ciOf(ProjectRef ref, String refName);

    /** Issue 列表（只读，state: open/closed/all） */
    List<IssueDTO> listIssues(ProjectRef ref, String state);

    /** Issue 详情（含评论，时间升序） */
    IssueDTO getIssue(ProjectRef ref, long number);

    /** Issue 评论（P2 写操作） */
    void commentIssue(ProjectRef ref, long number, String body);

    /** MR 评论（P2 写操作；各平台评论通道不同，实现负责归一） */
    void commentMR(ProjectRef ref, long number, String body);

    /** Release 列表（按创建时间倒序） */
    List<com.gewu.application.vcs.dto.ReleaseDTO> listReleases(ProjectRef ref);

    /** 基于已推送的 tag 创建 Release */
    com.gewu.application.vcs.dto.ReleaseDTO createRelease(ProjectRef ref, String tagName, String name, String notes, boolean prerelease);

    // ==================== P3：评审写操作 / Deploy Key / Release 附件 ====================

    /** 评审列表（GitLab 归一为 APPROVED 形态；不支持的平台返回空） */
    List<com.gewu.application.vcs.dto.ReviewDTO> reviews(ProjectRef ref, long mrNumber);

    /** 提交评审（event: APPROVE / REQUEST_CHANGES / COMMENT；不支持的平台抛错） */
    void submitReview(ProjectRef ref, long mrNumber, String event, String body);

    /** 注册仓库 Deploy Key（公钥；SSH 免密拉取闭环） */
    void registerDeployKey(ProjectRef ref, String title, String publicKey);

    /** Release 附件上传（沙箱文件字节；不支持的平台抛错）。返回资产名。 */
    String uploadReleaseAsset(ProjectRef ref, com.gewu.application.vcs.dto.ReleaseDTO release, String fileName, byte[] data);

    /** 能力清单 */
    com.gewu.application.vcs.dto.CapabilitiesDTO capabilities();
}
