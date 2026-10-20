package com.gewu.application.project;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.DevWorkspaceService;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.requirement.Requirement;
import com.gewu.domain.workspace.GitCredential;
import com.gewu.infrastructure.mapper.GitCredentialMapper;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

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
    private final GitCredentialMapper credentialMapper;
    private final ApiKeyCryptoService credentialCrypto;

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
        String effectiveUrl = resolveCredentialUrl(project, repoUrl);

        // 更新状态为 cloning
        project.setRepoUrl(repoUrl);
        project.setRepoBranch(resolvedBranch);
        project.setCloneStatus("cloning");
        project.setCloneError(null);
        projectMapper.updateById(project);

        // 创建目录并 clone（用户输入经 shellQuote 防注入，L3-SEC-002）
        sandboxClient.execCommand(sandboxId, "mkdir -p " + shellQuote("/workspace/" + localPath), 5);
        String cloneCmd = String.format("git clone --branch %s %s %s",
                shellQuote(resolvedBranch), shellQuote(effectiveUrl), shellQuote("/workspace/" + localPath));
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId, cloneCmd, 120);

        if (resp.getExitCode() != null && resp.getExitCode() == 0) {
            project.setCloneStatus("ready");
            project.setCloneError(null);
            ExecCommandResponse headResp = sandboxClient.execCommand(
                    sandboxId, "git -C " + shellQuote("/workspace/" + localPath) + " rev-parse HEAD", 10);
            project.setHeadCommit(headResp.getStdout().trim());
        } else {
            project.setCloneStatus("failed");
            String stderr = resp.getStderr() != null ? resp.getStderr().trim() : "";
            project.setCloneError(stderr.length() > 480 ? stderr.substring(0, 480) : stderr);
            log.warn("项目仓库 clone 失败: projectId={}, repo={}, stderr={}", projectId, repoUrl, stderr);
        }
        projectMapper.updateById(project);
        return project;
    }

    /**
     * 按凭证重写 https 仓库地址：私有仓库 clone 自动携带认证信息。
     * 优先项目级凭证（token 型注入 x-access-token，password 型注入 user:pass，ssh_key 型
     * 依赖容器内 ssh 配置不在此处理），无项目级凭证时回落用户级 token 凭证。
     * 匹配规则：凭证 host 与仓库 host 一致优先，否则取该用户第一个 token 型凭证。
     */
    private String resolveCredentialUrl(Project project, String repoUrl) {
        if (repoUrl == null || !repoUrl.startsWith("https://")) {
            return repoUrl;
        }
        try {
            String injected = injectProjectCredential(project, repoUrl);
            if (injected != null) {
                return injected;
            }
            return injectUserCredential(project.getOwnerId(), repoUrl);
        } catch (Exception e) {
            log.warn("凭证注入失败，按原始地址克隆: {}", e.getMessage());
            return repoUrl;
        }
    }

    /** 项目级凭证注入：token → x-access-token:{token}，password → {user}:{pass}（均 URL 编码）。未配置返回 null。 */
    private String injectProjectCredential(Project project, String repoUrl) {
        String encrypted = project.getRepoCredential();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        String secret = credentialCrypto.decrypt(encrypted);
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String type = project.getRepoCredentialType();
        if ("password".equals(type)) {
            String user = project.getRepoUsername() != null ? project.getRepoUsername() : "";
            return repoUrl.replaceFirst("https://",
                    "https://" + urlEncode(user) + ":" + urlEncode(secret) + "@");
        }
        // token 型（ssh_key 型走容器内 ssh 配置，不注入 URL）
        return repoUrl.replaceFirst("https://", "https://x-access-token:" + urlEncode(secret) + "@");
    }

    /** 用户级 token 凭证注入（历史逻辑：git_credential 表按 host 匹配）。 */
    private String injectUserCredential(String ownerId, String repoUrl) {
        String userId = com.gewu.common.context.UserContext.currentUserId() != null
                ? com.gewu.common.context.UserContext.currentUserId() : ownerId;
        if (userId == null) {
            return repoUrl;
        }
        var creds = credentialMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.gewu.domain.workspace.GitCredential>()
                        .eq(com.gewu.domain.workspace.GitCredential::getUserId, userId)
                        .eq(com.gewu.domain.workspace.GitCredential::getCredType, "token"));
        if (creds.isEmpty()) {
            return repoUrl;
        }
        String host = URI.create(repoUrl).getHost();
        com.gewu.domain.workspace.GitCredential picked = creds.stream()
                .filter(c -> c.getGitHost() != null && c.getGitHost().equalsIgnoreCase(host))
                .findFirst()
                .orElse(creds.get(0));
        String credValue = credentialCrypto.decrypt(picked.getCredValue());
        if (credValue == null || credValue.isBlank()) {
            return repoUrl;
        }
        return repoUrl.replaceFirst("https://", "https://x-access-token:" + urlEncode(credValue) + "@");
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 删除项目：清理沙箱卷内仓库目录并删除记录（clone 失败重来的通道） */
    @Transactional
    public void deleteProjectRepo(String projectId) {
        Project project = getProjectEntity(projectId);
        String sandboxId = devWorkspaceService.getDevSandboxId();
        if (project.getRepoLocalPath() != null) {
            sandboxClient.execCommand(sandboxId,
                    "rm -rf " + shellQuote("/workspace/" + project.getRepoLocalPath()), 15);
        }
        projectMapper.deleteById(projectId);
        log.info("项目已删除: projectId={}, path={}", projectId, project.getRepoLocalPath());
    }

    private static String shellQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "")) + "'";
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

        String pullDir = shellQuote("/workspace/" + localPath);
        String pullBranch = shellQuote(project.getRepoBranch() != null ? project.getRepoBranch() : "main");
        sandboxClient.execCommand(sandboxId,
                "git -C " + pullDir + " pull origin " + pullBranch, 60);

        ExecCommandResponse headResp = sandboxClient.execCommand(
                sandboxId, "git -C " + pullDir + " rev-parse HEAD", 10);
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
