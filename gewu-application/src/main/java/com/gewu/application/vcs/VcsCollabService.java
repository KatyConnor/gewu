package com.gewu.application.vcs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;
import com.gewu.application.vcs.dto.ReviewDTO;
import com.gewu.application.vcs.dto.CapabilitiesDTO;
import com.gewu.application.vcs.dto.ReleaseDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 托管平台协作服务（适配层门面）。
 *
 * <p>职责：项目成员鉴权 → repoUrl 平台识别 → token 凭证解密 → 组装 ProjectRef
 * → 委托对应 PlatformAdapter。协作能力是 token 凭证的增值能力：
 * password/SSH 型凭证（或平台无法识别）时显式报错降级，不影响 git 协议层功能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VcsCollabService {

    private final ProjectMapper projectEntityMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ApiKeyCryptoService credentialCrypto;
    private final com.gewu.application.workspace.DevWorkspaceService devWorkspaceService;
    private final com.gewu.application.sandbox.SandboxClient sandboxClient;

    private static final Map<PlatformType, PlatformAdapter> ADAPTERS = Map.of(
            PlatformType.GITHUB, new GithubAdapter(),
            PlatformType.GITLAB, new GitlabAdapter(),
            PlatformType.GITEE, new GiteeAdapter(),
            PlatformType.GITEA, new GiteaAdapter());

    // ==================== 主流程委托 ====================

    public List<MergeRequestDTO> listMRs(String projectId, String state) {
        return withAdapter(projectId, (ref, a) -> a.listMRs(ref, state));
    }

    public MergeRequestDTO createMR(String projectId, String source, String target, String title, String description) {
        return withAdapter(projectId, (ref, a) -> a.createMR(ref, source, target, title, description));
    }

    public MergeRequestDTO getMR(String projectId, long number) {
        return withAdapter(projectId, (ref, a) -> a.getMR(ref, number));
    }

    public CiStatusDTO ciOf(String projectId, String refName) {
        return withAdapter(projectId, (ref, a) -> a.ciOf(ref, refName));
    }

    public List<IssueDTO> listIssues(String projectId, String state) {
        return withAdapter(projectId, (ref, a) -> a.listIssues(ref, state));
    }

    public IssueDTO getIssue(String projectId, long number) {
        return withAdapter(projectId, (ref, a) -> a.getIssue(ref, number));
    }

    public void commentIssue(String projectId, long number, String body) {
        withAdapter(projectId, (ref, a) -> {
            a.commentIssue(ref, number, body);
            return null;
        });
    }

    public void commentMR(String projectId, long number, String body) {
        withAdapter(projectId, (ref, a) -> {
            a.commentMR(ref, number, body);
            return null;
        });
    }

    public List<com.gewu.application.vcs.dto.ReleaseDTO> listReleases(String projectId) {
        return withAdapter(projectId, (ref, a) -> a.listReleases(ref));
    }

    public com.gewu.application.vcs.dto.ReleaseDTO createRelease(String projectId, String tagName, String name, String notes, boolean prerelease) {
        return withAdapter(projectId, (ref, a) -> a.createRelease(ref, tagName, name, notes, prerelease));
    }

    // ==================== 解析与鉴权 ====================

    private ProjectRef resolve(String projectId) {
        Project project = projectEntityMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目不存在");
        }
        String userId = UserContext.currentUserId();
        boolean allowed = userId != null && (userId.equals(project.getOwnerId())
                || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)) > 0);
        if (!allowed) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权访问该项目协作信息");
        }
        String repoUrl = project.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "项目未配置 Git 仓库地址");
        }
        String host = parseHost(repoUrl);
        // 平台识别双轨：项目显式配置（自建实例）优先，否则按域名推断
        PlatformType platform;
        String ptCfg = project.getPlatformType();
        if (ptCfg != null && !ptCfg.isBlank()) {
            try {
                platform = PlatformType.valueOf(ptCfg.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "无效的托管平台类型配置: " + ptCfg);
            }
        } else {
            platform = PlatformType.detect(host);
        }
        if (platform == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "无法识别托管平台（支持 github/gitee/gitlab/gitea）——自建实例请在项目管理中显式配置平台类型与 API 地址");
        }
        PlatformAdapter adapter = ADAPTERS.get(platform);
        if (adapter == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该平台适配器暂未提供: " + platform);
        }
        if (!"token".equals(project.getRepoCredentialType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "协作功能需要访问令牌型凭证（当前: "
                            + (project.getRepoCredentialType() != null ? project.getRepoCredentialType() : "未配置")
                            + "）。请在项目管理中配置令牌后重试，或前往平台网页操作");
        }
        String token = credentialCrypto.decrypt(project.getRepoCredential());
        if (token == null || token.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仓库令牌解密失败，请在项目管理中重新配置");
        }
        String repoPath = parseRepoPath(repoUrl);
        if (repoPath == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "无法从仓库地址解析 owner/repo: " + repoUrl);
        }
        ProjectRef ref = new ProjectRef();
        ref.setPlatform(platform);
        ref.setToken(token);
        ref.setRepoPath(repoPath);
        applyEndpoints(ref, host, schemeOf(repoUrl), project.getApiBase());
        return ref;
    }

    private <T> T withAdapter(String projectId, java.util.function.BiFunction<ProjectRef, PlatformAdapter, T> fn) {
        ProjectRef ref = resolve(projectId);
        return fn.apply(ref, ADAPTERS.get(ref.getPlatform()));
    }

    // ==================== URL 解析 ====================

    /** 从 repoUrl 提取 host（https/http/git@/ssh:// 四形态） */
    static String parseHost(String repoUrl) {
        String s = repoUrl == null ? "" : repoUrl.trim();
        try {
            URI u = URI.create(s);
            if (u.getHost() != null) {
                return u.getHost();
            }
        } catch (Exception ignored) {
        }
        if (s.startsWith("git@")) {
            int c = s.indexOf(':');
            if (c > 4) {
                return s.substring(4, c);
            }
        }
        return null;
    }

    /** 从 repoUrl 提取 owner/repo 路径（剥 scheme/host 与 .git 后缀；GitLab subgroup 保留全路径） */
    static String parseRepoPath(String repoUrl) {
        String s = repoUrl == null ? "" : repoUrl.trim();
        String path = null;
        try {
            URI u = URI.create(s);
            if (u.getPath() != null && !u.getPath().isBlank()) {
                path = u.getPath();
            }
        } catch (Exception ignored) {
        }
        if (path == null && s.startsWith("git@")) {
            int c = s.indexOf(':');
            if (c > 4) {
                path = s.substring(c + 1);
            }
        }
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        return path.isBlank() ? null : path;
    }

    private static String schemeOf(String repoUrl) {
        return repoUrl != null && repoUrl.trim().startsWith("http://") ? "http://" : "https://";
    }

    // ==================== P3：评审写 / Deploy Key 闭环 / Release 附件 ====================

    public List<ReviewDTO> reviews(String projectId, long mrNumber) {
        return withAdapter(projectId, (ref, a) -> a.reviews(ref, mrNumber));
    }

    public void submitReview(String projectId, long mrNumber, String event, String body) {
        withAdapter(projectId, (ref, a) -> {
            a.submitReview(ref, mrNumber, event, body);
            return null;
        });
    }

    /** 能力清单（前端门控；受平台/凭证/自建版本影响） */
    public CapabilitiesDTO capabilities(String projectId) {
        ProjectRef ref = resolve(projectId);
        return ADAPTERS.get(ref.getPlatform()).capabilities();
    }

    /**
     * Deploy Key 注册闭环：沙箱生成 ed25519 密钥对 → 公钥注册到托管平台 →
     * 私钥 SM4 加密落项目凭证（type=ssh_key，复用既有克隆/拉取注入链路）→ 私钥即时注入沙箱。
     */
    public void registerDeployKey(String projectId, String title) {
        ProjectRef ref = resolve(projectId);
        if (!ADAPTERS.get(ref.getPlatform()).capabilities().isDeployKey()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该平台暂不支持 Deploy Key 注册");
        }
        String sandboxId = devWorkspaceService.ensureRunningSandboxId();
        // 生成密钥对（已存在则覆盖）
        sandboxClient.execCommand(sandboxId,
                "mkdir -p /workspace/.dwdbg && ssh-keygen -t ed25519 -N '' -f /workspace/.dwdbg/deploy_key -q",
                15);
        String publicKey = sandboxClient.execCommand(sandboxId,
                "cat /workspace/.dwdbg/deploy_key.pub", 10).getStdout().trim();
        if (publicKey.isBlank()) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "密钥对生成失败");
        }
        ADAPTERS.get(ref.getPlatform()).registerDeployKey(ref, title, publicKey);
        // 私钥落项目凭证（type=ssh_key 链路：clone/pull 时自动注入容器 .ssh/id_rsa）
        Project project = projectEntityMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目不存在");
        }
        String privateKey = sandboxClient.execCommand(sandboxId,
                "cat /workspace/.dwdbg/deploy_key", 10).getStdout();
        project.setRepoCredentialType("ssh_key");
        project.setRepoCredential(credentialCrypto.encrypt(privateKey));
        projectEntityMapper.updateById(project);
        // 私钥即时注入沙箱（不等下次克隆）
        sandboxClient.writeFile(sandboxId, ".ssh/id_rsa", privateKey);
        sandboxClient.execCommand(sandboxId, "chmod 600 /root/.ssh/id_rsa", 5);
        log.info("Deploy Key 已注册: projectId={}, title={}", projectId, title);
    }

    /** Release 附件上传：沙箱文件 → 平台（字节经后端中转） */
    public com.gewu.application.vcs.dto.ReleaseDTO uploadReleaseAsset(String projectId, String tagName,
                                                                      String sandboxPath, String assetName) {
        return withAdapter(projectId, (ref, a) -> {
            com.gewu.application.vcs.dto.ReleaseDTO release = a.listReleases(ref).stream()
                    .filter(r -> tagName.equals(r.getTagName()))
                    .findFirst()
                    .orElseThrow(() -> BusinessException.of(ResultCode.NOT_FOUND, "未找到该 tag 对应的 Release: " + tagName));
            byte[] data = devWorkspaceService.downloadFile(sandboxPath);
            if (data == null || data.length == 0) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "沙箱文件不存在或为空: " + sandboxPath);
            }
            String name = assetName != null && !assetName.isBlank() ? assetName
                    : sandboxPath.substring(sandboxPath.lastIndexOf('/') + 1);
            String asset = a.uploadReleaseAsset(ref, release, name, data);
            log.info("Release 附件已上传: projectId={}, tag={}, asset={}", projectId, tagName, asset);
            return release;
        });
    }

    /** 按平台推导 API/网页根地址（自建显式 apiBase 配置为后续增强） */
    private static void applyEndpoints(ProjectRef ref, String host, String scheme, String explicitApiBase) {
        String web = scheme + host;
        // 自建实例显式 API 地址优先（须为完整 http(s) 根地址）
        if (explicitApiBase != null && !explicitApiBase.isBlank() && explicitApiBase.trim().startsWith("http")) {
            ref.setApiBase(explicitApiBase.trim());
            ref.setWebBase(web);
            return;
        }
        switch (ref.getPlatform()) {
            case GITHUB -> {
                ref.setApiBase("https://api.github.com");
                ref.setWebBase(web);
            }
            case GITEE -> {
                ref.setApiBase("https://gitee.com/api/v5");
                ref.setWebBase(web);
            }
            case GITLAB -> {
                ref.setApiBase(web + "/api/v4");
                ref.setWebBase(web);
            }
            case GITEA -> {
                ref.setApiBase(web + "/api/v1");
                ref.setWebBase(web);
            }
        }
    }
}
