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
     * 克隆项目仓库到开发沙箱（按项目配置的 Git 地址与凭证）。
     */
    @Transactional
    public Project cloneProjectRepo(String projectId, String repoUrl, String branch) {
        Project project = getProjectEntity(projectId);
        String sandboxId = devWorkspaceService.ensureRunningSandboxId();
        String localPath = project.getRepoLocalPath() != null
                ? project.getRepoLocalPath()
                : "projects/" + projectId + "/repo";
        String resolvedBranch = branch != null && !branch.isBlank() ? branch
                : project.getRepoBranch() != null && !project.getRepoBranch().isBlank() ? project.getRepoBranch() : "main";
        String effectiveUrl = resolveCredentialUrl(project, repoUrl);
        // ssh_key 型凭证：私钥先注入容器（token/password 已在 URL 内携带）
        injectSshKeyIfConfigured(project, sandboxId);

        // 更新状态为 cloning（首次克隆同时落定容器内路径，pull/push/build/run 均依赖它）
        project.setRepoUrl(repoUrl);
        project.setRepoBranch(resolvedBranch);
        project.setRepoLocalPath(localPath);
        project.setCloneStatus("cloning");
        project.setCloneError(null);
        projectMapper.updateById(project);

        // 创建目录并 clone（用户输入经 shellQuote 防注入，L3-SEC-002；
        // GIT_SSH_COMMAND：容器内首连远程无 TTY，自动接受新主机指纹 + 禁用交互提示）
        sandboxClient.execCommand(sandboxId, "mkdir -p " + shellQuote("/workspace/" + localPath), 5);
        String cloneCmd = "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND=\"ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes\""
                + " git clone --branch " + shellQuote(resolvedBranch) + " " + shellQuote(effectiveUrl)
                + " " + shellQuote("/workspace/" + localPath);
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
     * 拉取项目最新代码：仓库未就绪（未克隆/克隆失败）时先按项目配置的 Git 地址与
     * 凭证自动克隆；已就绪则 pull 前按当前凭证刷新 origin 地址（凭证补配/变更后无需重克隆）。
     */
    @Transactional
    public Project gitPull(String projectId) {
        Project project = getProjectEntity(projectId);
        if (project.getRepoUrl() == null || project.getRepoUrl().isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "项目未配置 Git 仓库地址，请先在项目管理中配置");
        }
        if (!"ready".equals(project.getCloneStatus())) {
            return cloneProjectRepo(projectId, project.getRepoUrl(), project.getRepoBranch());
        }

        String sandboxId = devWorkspaceService.ensureRunningSandboxId();
        String localPath = project.getRepoLocalPath();
        injectSshKeyIfConfigured(project, sandboxId);
        // 凭证注入成功（地址被重写）→ 刷新 origin，防止克隆时无凭证导致后续 pull 认证失败
        String effectiveUrl = resolveCredentialUrl(project, project.getRepoUrl());
        if (!effectiveUrl.equals(project.getRepoUrl())) {
            sandboxClient.execCommand(sandboxId,
                    "git -C " + shellQuote("/workspace/" + localPath) + " remote set-url origin " + shellQuote(effectiveUrl), 10);
        }

        String pullDir = shellQuote("/workspace/" + localPath);
        String pullBranch = shellQuote(project.getRepoBranch() != null ? project.getRepoBranch() : "main");
        ExecCommandResponse pullResp = sandboxClient.execCommand(sandboxId,
                "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND=\"ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes\""
                        + " git -C " + pullDir + " pull origin " + pullBranch, 60);
        // 失败不静默：认证失败/冲突等直接抛出（与 DevWorkspace 拉取报错口径一致）
        if (pullResp.getExitCode() == null || pullResp.getExitCode() != 0) {
            String stderr = pullResp.getStderr() != null && !pullResp.getStderr().isBlank()
                    ? pullResp.getStderr().trim() : pullResp.getStdout();
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "Pull 失败: " + (stderr != null && stderr.length() > 300 ? stderr.substring(0, 300) : stderr));
        }

        ExecCommandResponse headResp = sandboxClient.execCommand(
                sandboxId, "git -C " + pullDir + " rev-parse HEAD", 10);
        project.setHeadCommit(headResp.getStdout().trim());
        projectMapper.updateById(project);
        return project;
    }

    /**
     * 按凭证重写仓库地址：私有仓库 clone/pull 自动携带认证信息。
     * 优先项目级凭证（token 型注入令牌用户名形态 x-access-token/oauth2（按平台），password 型注入 user:pass；
     * 两型遇到 ssh 形态地址先规范化为 https 再注入），ssh_key 型不注入 URL
     * （私钥经 {@link #injectSshKeyIfConfigured} 注入容器）。无项目级凭证时回落用户级 token 凭证。
     */
    private String resolveCredentialUrl(Project project, String repoUrl) {
        if (repoUrl == null) {
            return repoUrl;
        }
        boolean sshKeyCred = "ssh_key".equals(project.getRepoCredentialType());
        String url = repoUrl;
        if (!url.startsWith("https://") && !sshKeyCred) {
            url = normalizeGitUrl(url);
        }
        if (!url.startsWith("https://")) {
            return repoUrl;
        }
        try {
            String injected = injectProjectCredential(project, url);
            if (injected != null) {
                return injected;
            }
            return injectUserCredential(project.getOwnerId(), url);
        } catch (Exception e) {
            log.warn("凭证注入失败，按原始地址克隆: {}", e.getMessage());
            return url;
        }
    }

    /** 项目级凭证注入：token → {令牌用户名}:{token}（按平台），password → {user}:{pass}（均 URL 编码）。未配置返回 null。 */
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
        // token 型（ssh_key 型走容器内 ssh 配置，不注入 URL）；用户名形态按托管平台（Gitee/GitLab=oauth2）
        String host = URI.create(repoUrl).getHost();
        String tokenUser = com.gewu.application.vcs.PlatformType.tokenUserForHost(host);
        return repoUrl.replaceFirst("https://", "https://" + tokenUser + ":" + urlEncode(secret) + "@");
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
        String tokenUser = com.gewu.application.vcs.PlatformType.tokenUserForHost(host);
        return repoUrl.replaceFirst("https://", "https://" + tokenUser + ":" + urlEncode(credValue) + "@");
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
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    /** ssh 形式仓库地址规范化为 https（scp-like 与 ssh:// 两种）；https/其他原样返回 */
    private static String normalizeGitUrl(String repoUrl) {
        if (repoUrl == null) {
            return repoUrl;
        }
        String t = repoUrl.trim();
        if (t.startsWith("git@")) {
            int colon = t.indexOf(':');
            if (colon > 4) {
                return "https://" + t.substring(4, colon) + "/" + t.substring(colon + 1);
            }
        }
        if (t.startsWith("ssh://git@")) {
            return "https://" + t.substring("ssh://git@".length());
        }
        return t;
    }

    /**
     * 项目级 ssh_key 凭证注入沙箱：私钥写 /root/.ssh/id_rsa + known_hosts 预热
     * （token/password 型走 URL 注入，不在此处理）。失败仅告警不阻断——
     * 匿名可读仓库无凭证也能克隆。
     */
    private void injectSshKeyIfConfigured(Project project, String sandboxId) {
        if (!"ssh_key".equals(project.getRepoCredentialType())) {
            return;
        }
        String encrypted = project.getRepoCredential();
        if (encrypted == null || encrypted.isBlank()) {
            return;
        }
        try {
            String privateKey = credentialCrypto.decrypt(encrypted);
            if (privateKey == null || privateKey.isBlank()) {
                return;
            }
            String httpsUrl = normalizeGitUrl(project.getRepoUrl());
            String host = httpsUrl.startsWith("https://") ? URI.create(httpsUrl).getHost() : null;
            sandboxClient.execCommand(sandboxId, "mkdir -p /root/.ssh && chmod 700 /root/.ssh", 5);
            sandboxClient.writeFile(sandboxId, ".ssh/id_rsa", privateKey);
            sandboxClient.execCommand(sandboxId, "chmod 600 /root/.ssh/id_rsa", 5);
            if (host != null) {
                sandboxClient.execCommand(sandboxId,
                        "ssh-keyscan -t rsa " + shellQuote(host) + " >> /root/.ssh/known_hosts 2>/dev/null || true", 15);
            }
        } catch (Exception e) {
            log.warn("项目 SSH 私钥注入失败: projectId={}, err={}", project.getId(), e.getMessage());
        }
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
