package com.gewu.application.vcs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.vcs.dto.CiStatusDTO;
import com.gewu.application.vcs.dto.IssueDTO;
import com.gewu.application.vcs.dto.MergeRequestDTO;
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

    private static final Map<PlatformType, PlatformAdapter> ADAPTERS = Map.of(
            PlatformType.GITHUB, new GithubAdapter(),
            PlatformType.GITLAB, new GitlabAdapter());

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
        PlatformType platform = PlatformType.detect(host);
        if (platform == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "无法识别托管平台（支持 github/gitee/gitlab/gitea，自建实例平台类型配置即将支持）");
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
        applyEndpoints(ref, host, schemeOf(repoUrl));
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

    /** 按平台推导 API/网页根地址（自建显式 apiBase 配置为后续增强） */
    private static void applyEndpoints(ProjectRef ref, String host, String scheme) {
        String web = scheme + host;
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
