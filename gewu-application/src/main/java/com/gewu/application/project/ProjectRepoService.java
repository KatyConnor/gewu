package com.gewu.application.project;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.DevWorkspaceService;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.requirement.Requirement;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目仓库服务 - 管理 Git 仓库的 clone/pull/branch 操作.
 * <p>通过 SandboxClient HTTP 调用沙箱执行 git 命令，
 * 项目仓库统一路径: /workspace/projects/{projectId}/repo/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRepoService {

    private final ProjectMapper projectMapper;
    private final RequirementMapper requirementMapper;
    private final SandboxClient sandboxClient;
    private final DevWorkspaceService devWorkspaceService;

    /**
     * 克隆项目仓库到开发沙箱.
     */
    @Transactional
    public Project cloneProjectRepo(String projectId, String repoUrl, String branch) {
        Project project = getProjectEntity(projectId);
        String sandboxId = devWorkspaceService.getDevSandboxId();
        String localPath = project.getRepoLocalPath() != null
                ? project.getRepoLocalPath()
                : "projects/" + projectId + "/repo";
        String resolvedBranch = branch != null ? branch : "main";

        // 更新状态为 cloning
        project.setRepoUrl(repoUrl);
        project.setRepoBranch(resolvedBranch);
        project.setCloneStatus("cloning");
        projectMapper.updateById(project);

        // 创建目录并 clone
        sandboxClient.execCommand(sandboxId, "mkdir -p /workspace/" + localPath, 5);
        String cloneCmd = String.format("git clone --branch %s %s /workspace/%s",
                resolvedBranch, repoUrl, localPath);
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId, cloneCmd, 120);

        if (resp.getExitCode() != null && resp.getExitCode() == 0) {
            project.setCloneStatus("ready");
            ExecCommandResponse headResp = sandboxClient.execCommand(
                    sandboxId, "git -C /workspace/" + localPath + " rev-parse HEAD", 10);
            project.setHeadCommit(headResp.getStdout().trim());
        } else {
            project.setCloneStatus("failed");
            log.warn("项目仓库 clone 失败: projectId={}, repo={}, stderr={}",
                    projectId, repoUrl, resp.getStderr());
        }
        projectMapper.updateById(project);
        return project;
    }

    /**
     * Git pull 拉取最新代码.
     */
    @Transactional
    public Project gitPull(String projectId) {
        Project project = getProjectEntity(projectId);
        if (!"ready".equals(project.getCloneStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仓库未就绪，请先 clone");
        }
        String sandboxId = devWorkspaceService.getDevSandboxId();
        String localPath = project.getRepoLocalPath();

        sandboxClient.execCommand(sandboxId,
                "git -C /workspace/" + localPath + " pull origin " + project.getRepoBranch(), 60);

        ExecCommandResponse headResp = sandboxClient.execCommand(
                sandboxId, "git -C /workspace/" + localPath + " rev-parse HEAD", 10);
        project.setHeadCommit(headResp.getStdout().trim());
        projectMapper.updateById(project);
        return project;
    }

    /**
     * 为需求创建开发分支.
     * <p>分支命名: feature/{requirementCode}
     */
    @Transactional
    public Requirement createRequirementBranch(String projectId, String requirementId) {
        Project project = getProjectEntity(projectId);
        if (!"ready".equals(project.getCloneStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "项目仓库未就绪");
        }
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !projectId.equals(requirement.getProjectId())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "需求不存在或不属于该项目");
        }
        if (requirement.getGitBranch() != null) {
            return requirement; // 已有分支，直接返回
        }

        String sandboxId = devWorkspaceService.getDevSandboxId();
        String localPath = project.getRepoLocalPath();
        String branchName = "feature/" + requirement.getRequirementCode();

        // 确保在主分支上创建
        sandboxClient.execCommand(sandboxId,
                "git -C /workspace/" + localPath + " checkout " + project.getRepoBranch(), 10);
        // 创建并切换到需求分支
        sandboxClient.execCommand(sandboxId,
                "git -C /workspace/" + localPath + " checkout -b " + branchName, 10);

        requirement.setGitBranch(branchName);
        requirementMapper.updateById(requirement);
        log.info("需求分支创建: requirementId={}, branch={}", requirementId, branchName);
        return requirement;
    }

    /**
     * 切换分支.
     */
    public void switchBranch(String projectId, String branch) {
        Project project = getProjectEntity(projectId);
        String sandboxId = devWorkspaceService.getDevSandboxId();
        sandboxClient.execCommand(sandboxId,
                "git -C /workspace/" + project.getRepoLocalPath() + " checkout " + branch, 10);
    }

    private Project getProjectEntity(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }
}
