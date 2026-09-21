package com.gewu.application.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.dto.AddGitCredentialCommand;
import com.gewu.application.workspace.dto.CloneRepoCommand;
import com.gewu.application.workspace.dto.DevWorkspaceDTO;
import com.gewu.application.workspace.dto.GitCredentialDTO;
import com.gewu.application.workspace.dto.GitProjectDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.UserRole;
import com.gewu.domain.workspace.GitCredential;
import com.gewu.domain.workspace.Workspace;
import com.gewu.domain.workspace.WorkspaceProject;
import com.gewu.infrastructure.mapper.GitCredentialMapper;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.mapper.WorkspaceProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 开发工作空间服务 - 面向 DEV/TEST 角色的云端开发环境.
 * <p>通过 {@link SandboxClient} HTTP 调用 gewu-sandbox 服务（8082），
 * 支持 git clone/pull/push、编译构建、运行调试、Docker cp 文件传输。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevWorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceProjectMapper workspaceProjectMapper;
    private final GitCredentialMapper credMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final SandboxClient sandboxClient;
    private final ApiKeyCryptoService cryptoService;
    private final com.gewu.infrastructure.mapper.ProjectMapper projectEntityMapper;

    @Value("${gewu.sandbox.dev.image:gewu/dev-base:latest}")
    private String devImage;

    @Value("${gewu.sandbox.dev.cpu:2}")
    private int devCpu;

    @Value("${gewu.sandbox.dev.memory-mb:4096}")
    private int devMemoryMb;

    @Value("${gewu.sandbox.dev.disk-mb:20480}")
    private int devDiskMb;

    private static final Set<String> DEV_ROLES = Set.of(
            "ADMIN", "BACKEND_DEV", "FRONTEND_DEV", "TESTER");

    // ==================== 开发沙箱管理 ====================

    /** 获取开发工作空间状态 */
    public DevWorkspaceDTO getDevWorkspace() {
        Workspace ws = getOrCreateDevWorkspace();
        SandboxDTO sandbox = null;
        if (ws.getDevSandboxId() != null) {
            try {
                sandbox = sandboxClient.getSandbox(ws.getDevSandboxId());
            } catch (Exception e) {
                log.debug("开发沙箱查询失败: {}", e.getMessage());
            }
        }
        return DevWorkspaceDTO.builder()
                .workspaceId(ws.getId())
                .userId(ws.getUserId())
                .mode(ws.getMode())
                .devSandboxId(ws.getDevSandboxId())
                .sandboxStatus(sandbox != null ? sandbox.getStatus() : null)
                .sandboxStatusDesc(sandbox != null ? sandbox.getStatusDesc() : "未创建")
                .mountPath("/workspace")
                .build();
    }

    /** 启动开发沙箱（创建或恢复） */
    @Transactional
    public SandboxDTO startDevSandbox() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        return ensureDevSandboxForUser(userId);
    }

    /**
     * 按显式 userId 确保开发沙箱可用（创建或恢复）。
     * S9 F3：引擎文件工具在工具执行池线程调用，无 UserContext ThreadLocal，
     * 需要 userId 显式传入的入口。
     */
    @Transactional
    public SandboxDTO ensureDevSandboxForUser(String userId) {
        Workspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        if (ws == null) {
            if (!hasDevRole(userId)) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "开发工作空间仅限开发/测试角色使用");
            }
            ws = initDevWorkspace(userId);
        }
        if (!"dev".equals(ws.getMode())) {
            ws.setMode("dev");
            workspaceMapper.updateById(ws);
        }

        // 若已有沙箱，尝试恢复（DB 状态可能滞后于容器实际状态：
        // 容器被手动清理/异常退出时状态仍为 running，需探活校验，S9 F4；
        // 容器被彻底删除（外部 docker rm）时 getSandbox/startSandbox 均抛错，
        // 捕获后落到下方新建逻辑重新绑定——任何外部清理形态都能自愈）
        if (ws.getDevSandboxId() != null) {
            try {
                SandboxDTO existing = sandboxClient.getSandbox(ws.getDevSandboxId());
                String status = existing.getStatus();
                if ("running".equals(status) && probeSandboxAlive(ws.getDevSandboxId())) {
                    return existing;
                }
                if ("running".equals(status) || "stopped".equals(status) || "expired".equals(status)) {
                    return sandboxClient.startSandbox(ws.getDevSandboxId());
                }
            } catch (Exception e) {
                log.info("开发沙箱恢复失败，转为新建: sandboxId={}, err={}",
                        ws.getDevSandboxId(), e.getMessage());
            }
        }

        // 创建开发专用沙箱
        CreateSandboxCommand cmd = new CreateSandboxCommand();
        cmd.setSandboxName("dev-" + ws.getUserId().substring(0, 8));
        cmd.setImage(devImage);
        cmd.setCpuCores(devCpu);
        cmd.setMemoryMb(devMemoryMb);
        cmd.setDiskMb(devDiskMb);
        cmd.setNetworkEnabled(true);
        cmd.setSource("dev");
        cmd.setWorkspaceId(ws.getId());
        cmd.setTimeout(86400);

        SandboxDTO sandbox = sandboxClient.createSandbox(cmd);

        ws.setDevSandboxId(sandbox.getSandboxId());
        workspaceMapper.updateById(ws);

        // 注入 Git 凭证
        injectGitCredentials(ws.getUserId(), sandbox.getSandboxId());

        log.info("开发沙箱启动: userId={}, sandboxId={}", ws.getUserId(), sandbox.getSandboxId());
        return sandbox;
    }

    /** 沙箱探活：DB 状态 running 不代表容器存活，exec 一条空命令验证 */
    private boolean probeSandboxAlive(String sandboxId) {
        try {
            sandboxClient.execCommand(sandboxId, "echo ok", 5);
            return true;
        } catch (Exception e) {
            log.info("开发沙箱探活失败，尝试恢复: sandboxId={}, err={}", sandboxId, e.getMessage());
            return false;
        }
    }

    /** 停止开发沙箱（保留 Volume） */
    public void stopDevSandbox() {
        Workspace ws = getOrCreateDevWorkspace();
        if (ws.getDevSandboxId() != null) {
            sandboxClient.stopSandbox(ws.getDevSandboxId());
        }
    }

    // ==================== 文件操作 ====================

    /** 列出目录内容（通过 exec ls） */
    public String listFiles(String path) {
        String sandboxId = ensureRunningSandbox();
        String target = path != null && !path.isBlank() ? sanitizeWorkspaceRel(path) : "";
        String cmd = "ls -la --time-style=+%s "
                + shellQuote(target.isEmpty() ? "/workspace" : "/workspace/" + target);
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId, cmd, 10);
        return resp.getStdout();
    }

    /** 读取文件内容（通过 exec cat） */
    public String readFile(String filePath) {
        String sandboxId = ensureRunningSandbox();
        ExecCommandResponse resp = sandboxClient.execCommand(
                sandboxId, "cat " + shellQuote("/workspace/" + sanitizeWorkspaceRel(filePath)), 10);
        return resp.getStdout();
    }

    /** 写入文件内容（通过 Docker cp，绕过 CommandValidator） */
    public void writeFile(String filePath, String content) {
        String sandboxId = getDevSandboxId();
        sandboxClient.writeFile(sandboxId, filePath, content);
    }

    /** 上传文件（通过 Docker cp） */
    public void uploadFile(String remotePath, MultipartFile file) throws IOException {
        String sandboxId = getDevSandboxId();
        sandboxClient.uploadFile(sandboxId, remotePath, file);
    }

    /** 下载文件（通过 Docker cp） */
    public byte[] downloadFile(String filePath) {
        String sandboxId = getDevSandboxId();
        return sandboxClient.downloadFile(sandboxId, filePath);
    }

    /** 删除文件/目录（通过 exec rm） */
    public void deleteFile(String path) {
        String sandboxId = ensureRunningSandbox();
        sandboxClient.execCommand(sandboxId, "rm -rf " + shellQuote("/workspace/" + sanitizeWorkspaceRel(path)), 10);
    }

    /** 删除 dev 卷内指定相对路径（净化后 rm -rf），供项目删除等跨场景清理复用 */
    public void deleteWorkspaceDir(String relativePath) {
        String sandboxId = ensureRunningSandbox();
        sandboxClient.execCommand(sandboxId,
                "rm -rf " + shellQuote("/workspace/" + sanitizeWorkspaceRel(relativePath)), 15);
    }

    /** 执行命令（开发模式，宽松校验） */
    public ExecCommandResponse execCommand(String command, Integer timeout) {
        String sandboxId = ensureRunningSandbox();
        return sandboxClient.execCommand(sandboxId, command, timeout != null ? timeout : 60);
    }

    // ==================== Git 项目管理 ====================

    /** 克隆 Git 仓库 */
    @Transactional
    public WorkspaceProject cloneRepo(CloneRepoCommand command) {
        Workspace ws = getOrCreateDevWorkspace();
        String sandboxId = ensureRunningSandbox();
        String projectName = validateProjectName(command.getProjectName());
        String repoUrl = validateRepoUrl(command.getRepoUrl());
        String branch = validateBranch(command.getRepoBranch() != null ? command.getRepoBranch() : "main");
        String localPath = "projects/" + projectName;

        WorkspaceProject project = new WorkspaceProject();
        project.setWorkspaceId(ws.getId());
        project.setProjectName(projectName);
        project.setRepoUrl(repoUrl);
        project.setRepoBranch(branch);
        project.setLocalPath(localPath);
        project.setCloneStatus("cloning");
        project.setCreatedBy(UserContext.currentUserId());
        workspaceProjectMapper.insert(project);

        // 创建父目录
        sandboxClient.execCommand(sandboxId, "mkdir -p " + shellQuote("/workspace/" + localPath), 5);

        // 执行 git clone
        String cloneCmd = "git clone --branch " + shellQuote(branch) + " " + shellQuote(repoUrl)
                + " " + shellQuote("/workspace/" + localPath);
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId, cloneCmd, 120);

        if (resp.getExitCode() != null && resp.getExitCode() == 0) {
            project.setCloneStatus("ready");
            ExecCommandResponse headResp = sandboxClient.execCommand(
                    sandboxId, "git -C " + shellQuote("/workspace/" + localPath) + " rev-parse HEAD", 10);
            project.setHeadCommit(headResp.getStdout().trim());
        } else {
            project.setCloneStatus("failed");
            log.warn("Git clone 失败: repo={}, stderr={}", command.getRepoUrl(), resp.getStderr());
        }
        project.setLastSyncAt(System.currentTimeMillis());
        workspaceProjectMapper.updateById(project);

        return project;
    }

    /** 项目列表 */
    public List<GitProjectDTO> listProjects() {
        Workspace ws = getOrCreateDevWorkspace();
        List<WorkspaceProject> projects = workspaceProjectMapper.selectList(
                new LambdaQueryWrapper<WorkspaceProject>().eq(WorkspaceProject::getWorkspaceId, ws.getId()));
        return projects.stream().map(this::toProjectDTO).toList();
    }

    /** Git pull */
    public WorkspaceProject gitPull(String projectId) {
        WorkspaceProject project = getProjectEntity(projectId);
        String sandboxId = ensureRunningSandbox();

        String dir = "/workspace/" + sanitizeWorkspaceRel(project.getLocalPath());
        sandboxClient.execCommand(sandboxId,
                "git -C " + shellQuote(dir) + " pull origin " + shellQuote(project.getRepoBranch()), 60);

        ExecCommandResponse headResp = sandboxClient.execCommand(
                sandboxId, "git -C " + shellQuote(dir) + " rev-parse HEAD", 10);
        project.setHeadCommit(headResp.getStdout().trim());
        project.setLastSyncAt(System.currentTimeMillis());
        workspaceProjectMapper.updateById(project);
        return project;
    }

    /** Git commit + push（基于项目仓库路径） */
    public String gitCommitPush(String projectId, String message) {
        com.gewu.domain.project.Project project = projectEntityMapper.selectById(projectId);
        if (project == null || project.getRepoLocalPath() == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目仓库未初始化");
        }
        String sandboxId = ensureRunningSandbox();
        String dir = "/workspace/" + sanitizeWorkspaceRel(project.getRepoLocalPath());

        sandboxClient.execCommand(sandboxId, "git -C " + shellQuote(dir) + " add .", 10);
        sandboxClient.execCommand(sandboxId,
                "git -C " + shellQuote(dir) + " commit -m " + shellQuote(message != null ? message : ""), 30);
        String branch = project.getRepoBranch() != null ? project.getRepoBranch() : "main";
        ExecCommandResponse pushResp = sandboxClient.execCommand(
                sandboxId, "git -C " + shellQuote(dir) + " push origin " + shellQuote(branch), 60);

        return pushResp.getStdout() + (pushResp.getStderr().isEmpty() ? "" : "\n" + pushResp.getStderr());
    }

    /** 构建项目（基于项目仓库路径） */
    public ExecCommandResponse build(String projectId, String buildCmd) {
        com.gewu.domain.project.Project project = projectEntityMapper.selectById(projectId);
        if (project == null || project.getRepoLocalPath() == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目仓库未初始化");
        }
        String sandboxId = ensureRunningSandbox();
        return sandboxClient.execCommand(sandboxId,
                "cd " + shellQuote("/workspace/" + sanitizeWorkspaceRel(project.getRepoLocalPath()))
                        + " && " + buildCmd, 300);
    }

    /** 运行项目（基于项目仓库路径） */
    public ExecCommandResponse run(String projectId, String runCmd) {
        com.gewu.domain.project.Project project = projectEntityMapper.selectById(projectId);
        if (project == null || project.getRepoLocalPath() == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目仓库未初始化");
        }
        String sandboxId = ensureRunningSandbox();
        return sandboxClient.execCommand(sandboxId,
                "cd " + shellQuote("/workspace/" + sanitizeWorkspaceRel(project.getRepoLocalPath()))
                        + " && " + runCmd, 600);
    }

    // ==================== 路径与命令安全 ====================

    /** 项目名白名单：仅字母/数字/点/下划线/连字符（防路径拼接与 shell 注入） */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Git 分支名白名单 */
    private static final Pattern SAFE_BRANCH = Pattern.compile("^[A-Za-z0-9._/-]+$");

    /** Git 主机名白名单（用于拼接 git config 键，不允许 shell 元字符） */
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._-]+(:\\d{1,5})?$");

    /** 路径中禁止的 shell 元字符 */
    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[`;&|$<>()\\{\\}\\\\\\r\\n]");

    /**
     * 工作空间内相对路径净化：拒空、拒 .. 越界与 shell 元字符，返回去首尾斜杠的相对路径。
     * 前导 / 视为相对路径剥除——调用点统一自行拼接 /workspace/ 前缀。
     */
    public static String sanitizeWorkspaceRel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "工作空间路径不能为空");
        }
        String p = raw.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || p.contains("..") || UNSAFE_PATH_CHARS.matcher(p).find()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "非法工作空间路径: " + raw);
        }
        return p;
    }

    /** shell 单引号安全包裹：内部 ' 转义为 '\''（关闭-转义-重开），替代旧的删除式处理 */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String validateProjectName(String name) {
        if (name == null || name.isBlank() || !SAFE_NAME.matcher(name).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "项目名仅允许字母/数字/._-");
        }
        return name;
    }

    static String validateRepoUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")
                || url.startsWith("git@") || url.startsWith("ssh://"))) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仓库地址仅支持 http(s)/ssh 协议");
        }
        return url;
    }

    static String validateBranch(String branch) {
        if (branch == null || branch.isBlank() || !SAFE_BRANCH.matcher(branch).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "分支名非法");
        }
        return branch;
    }

    static String validateGitHost(String host) {
        if (host == null || !SAFE_HOST.matcher(host).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "Git 主机名非法: " + host);
        }
        return host;
    }

    // ==================== Git 凭证管理 ====================

    /** 添加 Git 凭证 */
    @Transactional
    public GitCredential addGitCredential(AddGitCredentialCommand command) {
        GitCredential cred = new GitCredential();
        cred.setUserId(UserContext.currentUserId());
        cred.setCredName(command.getCredName());
        cred.setCredType(command.getCredType());
        cred.setGitHost(command.getGitHost());
        cred.setCredValue(cryptoService.encrypt(command.getCredValue()));
        credMapper.insert(cred);
        log.info("Git 凭证添加: userId={}, name={}, type={}", cred.getUserId(), cred.getCredName(), cred.getCredType());
        return cred;
    }

    /** 凭证列表（不含明文） */
    public List<GitCredentialDTO> listGitCredentials() {
        String userId = UserContext.currentUserId();
        List<GitCredential> creds = credMapper.selectList(
                new LambdaQueryWrapper<GitCredential>().eq(GitCredential::getUserId, userId));
        return creds.stream().map(c -> GitCredentialDTO.builder()
                .credentialId(c.getId())
                .credName(c.getCredName())
                .credType(c.getCredType())
                .gitHost(c.getGitHost())
                .hasPublicKey(c.getSshPublicKey() != null)
                .createdAt(c.getCreatedAt())
                .build()).toList();
    }

    /** 删除凭证 */
    public void deleteGitCredential(String credentialId) {
        GitCredential cred = credMapper.selectById(credentialId);
        if (cred == null || !cred.getUserId().equals(UserContext.currentUserId())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "凭证不存在或无权访问");
        }
        credMapper.deleteById(credentialId);
    }

    /** 注入 Git 凭证到沙箱容器 */
    private void injectGitCredentials(String userId, String sandboxId) {
        List<GitCredential> creds = credMapper.selectList(
                new LambdaQueryWrapper<GitCredential>().eq(GitCredential::getUserId, userId));
        if (creds.isEmpty()) return;

        for (GitCredential cred : creds) {
            try {
                if ("ssh_key".equals(cred.getCredType())) {
                    String privateKey = cryptoService.decrypt(cred.getCredValue());
                    sandboxClient.writeFile(sandboxId, ".ssh/id_rsa", privateKey);
                    if (cred.getSshPublicKey() != null) {
                        sandboxClient.writeFile(sandboxId, ".ssh/id_rsa.pub", cred.getSshPublicKey());
                    }
                    sandboxClient.execCommand(sandboxId, "chmod 600 /root/.ssh/id_rsa", 5);
                } else if ("token".equals(cred.getCredType())) {
                    String token = cryptoService.decrypt(cred.getCredValue());
                    String host = validateGitHost(cred.getGitHost() != null ? cred.getGitHost() : "github.com");
                    sandboxClient.execCommand(sandboxId,
                            "git config --global credential.https://" + host + ".username token", 5);
                    sandboxClient.writeFile(sandboxId,
                            ".git-credentials", "https://token:" + token + "@" + host + "\n");
                    sandboxClient.execCommand(sandboxId,
                            "git config --global credential.helper store", 5);
                }
            } catch (Exception e) {
                log.warn("凭证注入失败: credName={}, error={}", cred.getCredName(), e.getMessage());
            }
        }

        // 配置 known_hosts
        try {
            sandboxClient.execCommand(sandboxId,
                    "ssh-keyscan -t rsa github.com >> /root/.ssh/known_hosts 2>/dev/null || true", 10);
        } catch (Exception ignored) {
        }
    }

    // ==================== 辅助方法 ====================

    private Workspace getOrCreateDevWorkspace() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Workspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        if (ws == null) {
            if (!hasDevRole(userId)) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "开发工作空间仅限开发/测试角色使用");
            }
            ws = initDevWorkspace(userId);
        }
        if (!"dev".equals(ws.getMode())) {
            ws.setMode("dev");
            workspaceMapper.updateById(ws);
        }
        return ws;
    }

    @Transactional
    public Workspace initDevWorkspace(String userId) {
        long quota = determineQuotaByRoles(userId);

        Workspace ws = new Workspace();
        ws.setUserId(userId);
        ws.setWorkspaceName("开发工作空间");
        ws.setStoragePath("workspaces/" + userId + "/");
        ws.setQuotaBytes(quota);
        ws.setUsedBytes(0L);
        ws.setFileCount(0);
        ws.setStatus(1);
        ws.setMode("dev");
        workspaceMapper.insert(ws);

        log.info("开发工作空间初始化: userId={}, workspaceId={}", userId, ws.getId());
        return ws;
    }

    private boolean hasDevRole(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) return false;
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        return roles.stream().map(Role::getRoleCode).anyMatch(DEV_ROLES::contains);
    }

    private long determineQuotaByRoles(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) return 1L * 1024 * 1024 * 1024;
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        List<String> roleCodes = roles.stream().map(Role::getRoleCode).toList();
        if (roleCodes.contains("ADMIN")) return 20L * 1024 * 1024 * 1024;
        if (DEV_ROLES.stream().anyMatch(roleCodes::contains)) return 5L * 1024 * 1024 * 1024;
        return 1L * 1024 * 1024 * 1024;
    }

    /** 获取开发沙箱 ID（供 ProjectRepoService 等外部调用） */
    public String getDevSandboxId() {
        Workspace ws = getOrCreateDevWorkspace();
        if (ws.getDevSandboxId() == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "开发沙箱未创建，请先启动");
        }
        return ws.getDevSandboxId();
    }

    /** 确保沙箱正在运行，返回 sandboxId */
    private String ensureRunningSandbox() {
        String sandboxId = getDevSandboxId();
        SandboxDTO sandbox = sandboxClient.getSandbox(sandboxId);
        if (!"running".equals(sandbox.getStatus())) {
            log.info("开发沙箱未运行，自动启动: sandboxId={}", sandboxId);
            sandboxClient.startSandbox(sandboxId);
        }
        return sandboxId;
    }

    private WorkspaceProject getProjectEntity(String projectId) {
        Workspace ws = getOrCreateDevWorkspace();
        WorkspaceProject project = workspaceProjectMapper.selectById(projectId);
        if (project == null || !project.getWorkspaceId().equals(ws.getId())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目不存在或无权访问");
        }
        return project;
    }

    public GitProjectDTO toProjectDTO(WorkspaceProject p) {
        return GitProjectDTO.builder()
                .projectId(p.getId())
                .workspaceId(p.getWorkspaceId())
                .projectName(p.getProjectName())
                .repoUrl(p.getRepoUrl())
                .repoBranch(p.getRepoBranch())
                .localPath(p.getLocalPath())
                .cloneStatus(p.getCloneStatus())
                .lastSyncAt(p.getLastSyncAt())
                .headCommit(p.getHeadCommit())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
