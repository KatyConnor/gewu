package com.gewu.application.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.dto.DevWorkspaceDTO;
import com.gewu.application.workspace.dto.BlameLineDTO;
import com.gewu.application.workspace.dto.CompareDTO;
import com.gewu.application.workspace.dto.GrepHitDTO;
import com.gewu.application.workspace.dto.TagDTO;
import com.gewu.application.workspace.dto.GitBranchesDTO;
import com.gewu.application.workspace.dto.GitLogEntryDTO;
import com.gewu.application.workspace.dto.GitOpCommand;
import com.gewu.application.workspace.dto.GitProjectDTO;
import com.gewu.application.workspace.dto.GitStashDTO;
import com.gewu.application.workspace.dto.ReflogEntryDTO;
import com.gewu.application.workspace.dto.GitStatusDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.UserRole;
import com.gewu.domain.workspace.GitCredential;
import com.gewu.domain.workspace.Workspace;
import com.gewu.infrastructure.mapper.GitCredentialMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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
    private final ProjectMemberMapper projectMemberMapper;
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

    /**
     * 结构化目录列表（IDEA 式工作台文件树数据源）：把 ls -la 原文解析为
     * 名称/类型/大小/修改时间条目，跳过 . .. 与 total 行；符号链接按文件处理并剪掉 " -> target"。
     */
    public java.util.List<com.gewu.application.workspace.dto.WorkspaceFileEntry> listEntries(String path) {
        String sandboxId = ensureRunningSandbox();
        String target = path != null && !path.isBlank() ? sanitizeWorkspaceRel(path) : "";
        String cmd = "ls -la --time-style=+%s "
                + shellQuote(target.isEmpty() ? "/workspace" : "/workspace/" + target);
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId, cmd, 10);
        java.util.List<com.gewu.application.workspace.dto.WorkspaceFileEntry> entries = new java.util.ArrayList<>();
        for (String line : resp.getStdout().split("\n")) {
            if (line.isBlank() || line.startsWith("total ")) {
                continue;
            }
            // 格式: perms links owner group size mtime name...
            String[] parts = line.trim().split("\\s+", 7);
            if (parts.length < 7) {
                continue;
            }
            String name = parts[6];
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) {
                name = name.substring(0, arrow);
            }
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            boolean dir = parts[0].startsWith("d");
            long size;
            long mtime;
            try {
                size = Long.parseLong(parts[4]);
                mtime = Long.parseLong(parts[5]);
            } catch (NumberFormatException e) {
                continue;
            }
            entries.add(new com.gewu.application.workspace.dto.WorkspaceFileEntry(name, dir, size, mtime));
        }
        return entries;
    }

    /** 读取文件内容（通过 exec cat） */
    public String readFile(String filePath) {
        String sandboxId = ensureRunningSandbox();
        ExecCommandResponse resp = sandboxClient.execCommand(
                sandboxId, "cat " + shellQuote("/workspace/" + sanitizeWorkspaceRel(filePath)), 10);
        return resp.getStdout();
    }

    /** 写入文件内容（通过 Docker cp，绕过 CommandValidator）。客户端净化 + 服务端 validatePath 双层防御。 */
    public void writeFile(String filePath, String content) {
        String sandboxId = getDevSandboxId();
        sandboxClient.writeFile(sandboxId, sanitizeWorkspaceRel(filePath), content);
    }

    /** 与 WorkspaceService 同款黑名单（L-1 对齐）：可执行载荷不入容器 */
    private static final java.util.Set<String> BLOCKED_UPLOAD_EXTENSIONS = java.util.Set.of(
            ".exe", ".bat", ".cmd", ".sh", ".ps1", ".dll", ".so", ".dylib");
    private static final long DEV_UPLOAD_MAX_BYTES = 100L * 1024 * 1024;

    /** 上传文件（通过 Docker cp）。客户端净化 + 服务端 validatePath 双层防御。 */
    public void uploadFile(String remotePath, MultipartFile file) throws IOException {
        // L-1（UCSS SEC-FILE-01）：文件名黑名单 + 应用层大小校验
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件名不能为空");
        }
        String lower = name.toLowerCase();
        for (String ext : BLOCKED_UPLOAD_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的文件类型: " + ext);
            }
        }
        if (file.getSize() > DEV_UPLOAD_MAX_BYTES) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "文件超过 100MB 限制");
        }
        String sandboxId = getDevSandboxId();
        sandboxClient.uploadFile(sandboxId, sanitizeWorkspaceRel(remotePath), file);
    }

    /** 下载文件（通过 Docker cp）。客户端净化 + 服务端 validatePath 双层防御。 */
    public byte[] downloadFile(String filePath) {
        String sandboxId = getDevSandboxId();
        return sandboxClient.downloadFile(sandboxId, sanitizeWorkspaceRel(filePath));
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

    /**
     * 项目列表：取「我的项目」中分配给当前用户的项目（project_member 成员行驱动，
     * 与 /projects/my 同口径）。Git 地址与凭证来自项目配置，克隆/拉取由
     * ProjectRepoService 按项目级凭证执行，用户不再手动录入仓库信息。
     */
    public List<GitProjectDTO> listProjects() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId));
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> projectIds = members.stream().map(ProjectMember::getProjectId).toList();
        return projectEntityMapper.selectBatchIds(projectIds).stream()
                .map(this::toProjectDTO)
                .toList();
    }

    /** 领域项目 → 工作空间项目 DTO（localPath 取仓库容器内路径，未克隆时按约定路径预置） */
    public GitProjectDTO toProjectDTO(Project p) {
        return GitProjectDTO.builder()
                .projectId(p.getId())
                .projectName(p.getProjectName())
                .repoUrl(p.getRepoUrl())
                // 未配置/未克隆时如实传 null（克隆成功后回填远程默认分支），前端显示「默认分支」
                .repoBranch(p.getRepoBranch())
                .localPath(p.getRepoLocalPath() != null ? p.getRepoLocalPath() : "projects/" + p.getId() + "/repo")
                .cloneStatus(p.getCloneStatus() != null ? p.getCloneStatus() : "pending")
                .cloneError(p.getCloneError())
                .headCommit(p.getHeadCommit())
                .repoCredentialType(p.getRepoCredentialType())
                .repoUsername(p.getRepoUsername())
                .hasRepoCredential(p.getRepoCredential() != null && !p.getRepoCredential().isBlank())
                .build();
    }

    // ==================== Git 常规操作（IDEA 式 Git 菜单操作集） ====================
    // 项目仓库路径 = 领域 Project.repoLocalPath；操作统一经 gitExec（SSH 非交互环境 +
    // 失败不静默）；入口统一 requireGitProject（成员/所有者门 + 仓库就绪门）。

    /** Git 操作入口校验：项目存在 → 当前用户为所有者或项目成员 → 仓库已就绪（包内共享：调试服务复用） */
    com.gewu.domain.project.Project requireGitProject(String projectId) {
        com.gewu.domain.project.Project project = projectEntityMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目不存在");
        }
        String userId = UserContext.currentUserId();
        boolean allowed = userId != null && (userId.equals(project.getOwnerId())
                || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)) > 0);
        if (!allowed) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权访问该项目仓库");
        }
        if (!"ready".equals(project.getCloneStatus()) || project.getRepoLocalPath() == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "项目仓库未就绪，请先拉取代码");
        }
        return project;
    }

    /** git 命令统一执行：仓库目录 + SSH 非交互环境；失败抛错（stderr 优先，截断 300 字符） */
    private String gitExec(com.gewu.domain.project.Project project, String args, int timeoutSec) {
        String sandboxId = ensureRunningSandboxId();
        String dir = "/workspace/" + sanitizeWorkspaceRel(project.getRepoLocalPath());
        ExecCommandResponse resp = sandboxClient.execCommand(sandboxId,
                "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND=\"ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes\""
                        + " git -C " + shellQuote(dir) + " " + args, timeoutSec);
        if (resp.getExitCode() == null || resp.getExitCode() != 0) {
            String err = resp.getStderr() != null && !resp.getStderr().isBlank() ? resp.getStderr() : resp.getStdout();
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "Git 操作失败: " + (err != null && err.length() > 300 ? err.substring(0, 300) : err));
        }
        return resp.getStdout() != null ? resp.getStdout() : "";
    }

    /** 工作区状态（分支/上游/领先落后/变更清单，git status porcelain 解析） */
    public GitStatusDTO gitStatus(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String out = gitExec(project, "status --porcelain=v1 -b", 15);
        GitStatusDTO dto = new GitStatusDTO();
        for (String line : out.split("\n")) {
            if (line.startsWith("## ")) {
                String head = line.substring(3).trim();
                int dots = head.indexOf("...");
                if (dots > 0) {
                    dto.setBranch(head.substring(0, dots));
                    String rest = head.substring(dots + 3);
                    dto.setUpstream(rest.split("\\s+")[0]);
                    Matcher m = Pattern.compile("ahead (\\d+)").matcher(rest);
                    if (m.find()) {
                        dto.setAhead(Integer.parseInt(m.group(1)));
                    }
                    m = Pattern.compile("behind (\\d+)").matcher(rest);
                    if (m.find()) {
                        dto.setBehind(Integer.parseInt(m.group(1)));
                    }
                } else {
                    dto.setBranch(head.split("\\s+")[0]);
                }
            } else if (line.length() >= 4) {
                char x = line.charAt(0);
                char y = line.charAt(1);
                String path = line.substring(3).trim();
                GitStatusDTO.Change ch = new GitStatusDTO.Change();
                int arrow = path.lastIndexOf(" -> ");
                if (arrow > 0) {
                    ch.setRenameFrom(path.substring(0, arrow));
                    path = path.substring(arrow + 4);
                }
                ch.setPath(path);
                ch.setUntracked(x == '?' && y == '?');
                ch.setStaged(!ch.isUntracked() && x != ' ');
                ch.setStatusDesc(statusDescOf(x, y, ch.isUntracked()));
                dto.getChanges().add(ch);
            }
        }
        return dto;
    }

    private static String statusDescOf(char x, char y, boolean untracked) {
        char c = untracked ? '?' : (x != ' ' ? x : y);
        return switch (c) {
            case 'A' -> "新增";
            case 'D' -> "删除";
            case 'R' -> "重命名";
            case 'C' -> "复制";
            case '?' -> "新文件";
            default -> "修改";
        };
    }

    /** 提交历史（单行格式 + \x1f 分隔，规避消息内制表符干扰） */
    public List<GitLogEntryDTO> gitLog(String projectId, Integer limit) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        int n = limit != null && limit > 0 && limit <= 500 ? limit : 100;
        String out = gitExec(project, "log -n " + n + " --pretty=format:%H%x1f%an%x1f%at%x1f%s", 30);
        List<GitLogEntryDTO> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\u001f", 4);
            if (parts.length < 4) {
                continue;
            }
            GitLogEntryDTO e = new GitLogEntryDTO();
            e.setHash(parts[0]);
            e.setShortHash(parts[0].length() > 7 ? parts[0].substring(0, 7) : parts[0]);
            e.setAuthor(parts[1]);
            try {
                e.setTimestamp(Long.parseLong(parts[2]));
            } catch (NumberFormatException ignored) {
            }
            e.setMessage(parts[3]);
            list.add(e);
        }
        return list;
    }

    /** 分支列表（本地 + 远程跟踪，HEAD 标记解析当前分支） */
    public GitBranchesDTO gitBranches(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        GitBranchesDTO dto = new GitBranchesDTO();
        for (String line : gitExec(project, "branch --format=%(HEAD)%(refname:short)", 15).split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("*")) {
                dto.setCurrent(t.substring(1));
            } else {
                dto.getLocal().add(t);
            }
        }
        for (String line : gitExec(project, "branch -r --format=%(refname:short)", 30).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.contains("HEAD")) {
                dto.getRemote().add(t);
            }
        }
        return dto;
    }

    /** stash 记录列表 */
    public List<GitStashDTO> gitStashList(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String out = gitExec(project, "stash list --pretty=format:%gd%x1f%s", 15);
        List<GitStashDTO> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\u001f", 2);
            GitStashDTO s = new GitStashDTO();
            Matcher m = Pattern.compile("\\{(\\d+)\\}").matcher(parts[0]);
            if (m.find()) {
                s.setIndex(Integer.parseInt(m.group(1)));
            }
            s.setMessage(parts.length > 1 ? parts[1] : "");
            list.add(s);
        }
        return list;
    }

    /** 单文件工作区差异（vs HEAD；未跟踪文件为空，由前端回落展示全文） */
    public String gitFileDiff(String projectId, String path) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        return gitExec(project, "diff HEAD -- " + shellQuote(sanitizeWorkspaceRel(path)), 30);
    }

    /** 提交（IDEA 提交对话框语义：勾选文件 add 后 commit；无勾选则提交已暂存内容） */
    public void gitCommit(String projectId, GitOpCommand cmd) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        List<String> files = cmd.getFiles();
        if (files != null && !files.isEmpty()) {
            StringBuilder add = new StringBuilder("add --");
            for (String f : files) {
                add.append(" ").append(shellQuote(sanitizeWorkspaceRel(f)));
            }
            gitExec(project, add.toString(), 30);
        }
        String message = cmd.getMessage() != null ? cmd.getMessage().trim() : "";
        boolean amend = Boolean.TRUE.equals(cmd.getAmend());
        if (message.isEmpty() && !amend) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "提交信息不能为空");
        }
        StringBuilder commit = new StringBuilder("commit ");
        if (!message.isEmpty()) {
            commit.append("-m ").append(shellQuote(message)).append(" ");
        }
        if (amend) {
            commit.append("--amend --no-edit ");
        }
        gitExec(project, commit.toString(), 60);
    }

    /** 推送当前分支（-u：远程无同名分支时建立跟踪，已跟踪时幂等） */
    public String gitPush(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        return gitExec(project, "push -u origin HEAD", 120);
    }

    /** 获取远程更新（含 prune 清理已删除远程分支的跟踪引用） */
    public void gitFetch(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        gitExec(project, "fetch --prune", 120);
    }

    /** 创建分支（可选立即检出） */
    public void gitCreateBranch(String projectId, String name, Boolean checkout) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String branch = validateBranch(name);
        gitExec(project, "branch " + shellQuote(branch), 15);
        if (Boolean.TRUE.equals(checkout)) {
            gitExec(project, "checkout " + shellQuote(branch), 15);
        }
    }

    /** 切换分支 */
    public void gitCheckout(String projectId, String branch) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        gitExec(project, "checkout " + shellQuote(validateBranch(branch)), 30);
    }

    /** 删除本地分支（-d 安全删除：未合入当前分支的拒绝删除；当前分支不可删） */
    public void gitDeleteBranch(String projectId, String name) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String branch = validateBranch(name);
        if (branch.equals(currentBranchOf(project))) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不能删除当前所在分支");
        }
        gitExec(project, "branch -d " + shellQuote(branch), 15);
    }

    /** 合并分支到当前分支（冲突时退出码非 0，错误信息透出） */
    public void gitMerge(String projectId, String branch) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        gitExec(project, "merge --no-edit " + shellQuote(validateBranch(branch)), 60);
    }

    /** 暂存更改（-u 含未跟踪文件，对齐 IDEA stash 默认行为） */
    public void gitStash(String projectId, String message) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String msg = message != null ? message.trim() : "";
        gitExec(project, "stash push -u" + (msg.isEmpty() ? "" : " -m " + shellQuote(msg)), 30);
    }

    /** 弹出 stash（应用到工作区并从栈中移除） */
    public void gitStashPop(String projectId, Integer index) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        int idx = index != null && index >= 0 && index <= 999 ? index : 0;
        gitExec(project, "stash pop " + shellQuote("stash@{" + idx + "}"), 60);
    }

    /** 回滚（IDEA Rollback 语义）：跟踪文件 reset+checkout 还原；未跟踪/新增文件 clean 清除 */
    public void gitRollback(String projectId, List<String> paths, List<String> untrackedPaths) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        boolean hasTracked = paths != null && !paths.isEmpty();
        boolean hasUntracked = untrackedPaths != null && !untrackedPaths.isEmpty();
        if (!hasTracked && !hasUntracked) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "未选择要回滚的文件");
        }
        if (hasTracked) {
            StringBuilder reset = new StringBuilder("reset -q HEAD --");
            for (String f : paths) {
                reset.append(" ").append(shellQuote(sanitizeWorkspaceRel(f)));
            }
            gitExec(project, reset.toString(), 30);
            StringBuilder checkout = new StringBuilder("checkout --");
            for (String f : paths) {
                checkout.append(" ").append(shellQuote(sanitizeWorkspaceRel(f)));
            }
            gitExec(project, checkout.toString(), 30);
        }
        if (hasUntracked) {
            StringBuilder clean = new StringBuilder("clean -f --");
            for (String f : untrackedPaths) {
                clean.append(" ").append(shellQuote(sanitizeWorkspaceRel(f)));
            }
            gitExec(project, clean.toString(), 30);
        }
    }

    /** 项目沙箱 id（归档等跨仓场景复用） */
    private String devWorkspaceSandbox() {
        return ensureRunningSandboxId();
    }

    private String currentBranchOf(com.gewu.domain.project.Project project) {
        return gitExec(project, "rev-parse --abbrev-ref HEAD", 10).trim();
    }

    /** 全项目文件清单（快速检索数据源）：已跟踪 + 未忽略新文件，Java 侧模糊过滤排序 */
    public List<String> gitListFiles(String projectId, String query, Integer limit) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        int cap = limit != null && limit > 0 && limit <= 200 ? limit : 50;
        // -z：NUL 分隔，规避路径内空格/特殊字符解析歧义
        String out = gitExec(project, "ls-files --cached --others --exclude-standard -z", 30);
        List<String> files = new ArrayList<>();
        for (String f : out.split("\u0000", -1)) {
            if (!f.isBlank()) {
                files.add(f);
            }
        }
        if (query == null || query.isBlank()) {
            return files.size() > cap ? new ArrayList<>(files.subList(0, cap)) : files;
        }
        String q = query.trim().toLowerCase();
        List<int[]> scored = new ArrayList<>(); // [score, index]，score 越小越靠前
        for (int i = 0; i < files.size(); i++) {
            int s = fuzzyScore(files.get(i), q);
            if (s >= 0) {
                scored.add(new int[]{s, i});
            }
        }
        scored.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, scored.size()); i++) {
            result.add(files.get(scored.get(i)[1]));
        }
        return result;
    }

    /** 模糊匹配得分（IDEA 检索手感）：文件名包含 0 分最优，全路径包含次之，子序列按跳转数递增；不命中 -1 */
    private static int fuzzyScore(String path, String lowerQuery) {
        String p = path.toLowerCase();
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.contains(lowerQuery)) {
            return 0;
        }
        if (p.contains(lowerQuery)) {
            return 10;
        }
        int pi = 0;
        int jumps = 0;
        for (char c : lowerQuery.toCharArray()) {
            int found = p.indexOf(c, pi);
            if (found < 0) {
                return -1;
            }
            if (found > pi) {
                jumps++;
            }
            pi = found + 1;
        }
        return 100 + jumps;
    }

    /** 项目全局文本搜索（-F 字面量匹配规避正则注入；限行数防大结果；exit=1 无命中非错误） */
    public List<GrepHitDTO> gitGrep(String projectId, String query, Integer limit) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "搜索内容不能为空");
        }
        int cap = limit != null && limit > 0 && limit <= 300 ? limit : 100;
        String repo = "/workspace/" + sanitizeWorkspaceRel(project.getRepoLocalPath());
        ExecCommandResponse resp = sandboxClient.execCommand(ensureRunningSandboxId(),
                "grep -rnIF --exclude-dir=.git -e " + shellQuote(q) + " " + shellQuote(repo), 30);
        if (resp.getExitCode() != null && resp.getExitCode() == 2) {
            String err = resp.getStderr() != null ? resp.getStderr() : "";
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "搜索失败: " + (err.length() > 300 ? err.substring(0, 300) : err));
        }
        List<GrepHitDTO> hits = new ArrayList<>();
        String prefix = repo + "/";
        for (String line : (resp.getStdout() != null ? resp.getStdout() : "").split("\n")) {
            if (line.isBlank() || hits.size() >= cap) {
                continue;
            }
            int c1 = line.indexOf(':');
            int c2 = c1 >= 0 ? line.indexOf(':', c1 + 1) : -1;
            if (c1 <= 0 || c2 <= c1) {
                continue;
            }
            GrepHitDTO h = new GrepHitDTO();
            String path = line.substring(0, c1);
            h.setPath(path.startsWith(prefix) ? path.substring(prefix.length()) : path);
            try {
                h.setLine(Integer.parseInt(line.substring(c1 + 1, c2)));
            } catch (NumberFormatException e) {
                continue;
            }
            h.setText(line.substring(c2 + 1));
            hits.add(h);
        }
        return hits;
    }

    // ==================== 标签 / blame / 双分支对比（git 协议层，全托管平台通用） ====================

    /** 标签列表（for-each-ref，按创建时间倒序） */
    public List<TagDTO> listTags(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String out = gitExec(project,
                "for-each-ref refs/tags --sort=-creatordate --format=%(refname:short)%09%(creatordate:format:%Y-%m-%d %H:%M)%09%(objectname:short)", 15);
        List<TagDTO> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || parts[0].isBlank()) {
                continue;
            }
            TagDTO t = new TagDTO();
            t.setName(parts[0]);
            t.setDate(parts[1]);
            t.setSha(parts[2]);
            list.add(t);
        }
        return list;
    }

    /** 创建附注标签（可选推送到远程） */
    public List<TagDTO> createTag(String projectId, String name, String message, boolean push) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String tag = validateBranch(name);
        if (message == null || message.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "标签说明不能为空");
        }
        gitExec(project, "tag -a " + shellQuote(tag) + " -m " + shellQuote(message.trim()), 15);
        if (push) {
            gitExec(project, "push origin refs/tags/" + shellQuote(tag), 60);
        }
        return listTags(projectId);
    }

    /** 删除标签（可选同步删除远程标签） */
    public List<TagDTO> deleteTag(String projectId, String name, boolean pushRemote) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String tag = validateBranch(name);
        gitExec(project, "tag -d " + shellQuote(tag), 15);
        if (pushRemote) {
            gitExec(project, "push origin :refs/tags/" + shellQuote(tag), 60);
        }
        return listTags(projectId);
    }

    /** 行级归属（git blame --line-porcelain，每行完整头块便于解析） */
    public List<BlameLineDTO> blame(String projectId, String path) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String out = gitExec(project, "blame --line-porcelain -- " + shellQuote(sanitizeWorkspaceRel(path)), 30);
        List<BlameLineDTO> list = new ArrayList<>();
        String sha = null, author = null, summary = null;
        Long time = null;
        int lineNo = 0;
        for (String line : out.split("\n", -1)) {
            if (line.startsWith("\t")) {
                lineNo++;
                BlameLineDTO b = new BlameLineDTO();
                b.setLine(lineNo);
                b.setSha(sha != null && sha.length() > 8 ? sha.substring(0, 8) : sha);
                b.setAuthor(author);
                b.setTime(time);
                b.setSummary(summary);
                b.setContent(line.substring(1));
                list.add(b);
                continue;
            }
            if (line.length() >= 40 && line.substring(0, 40).matches("[0-9a-f]{40}.*")) {
                sha = line.substring(0, 40);
                author = null;
                summary = null;
                time = null;
                continue;
            }
            if (line.startsWith("author ")) {
                author = line.substring("author ".length());
            } else if (line.startsWith("author-time ")) {
                try {
                    time = Long.parseLong(line.substring("author-time ".length())) * 1000;
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("summary ")) {
                summary = line.substring("summary ".length());
            }
        }
        return list;
    }

    // ==================== 历史操作扩展（cherry-pick / revert / 归档 / reflog 恢复） ====================

    /** 摘取提交到当前分支（冲突时 git 非零退出透出错误） */
    public void cherryPick(String projectId, String sha) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        gitExec(project, "cherry-pick " + shellQuote(sha), 60);
    }

    /** 撤销提交（生成反向提交，安全不改历史） */
    public void revertCommit(String projectId, String sha) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        gitExec(project, "revert --no-edit " + shellQuote(sha), 60);
    }

    /** 归档导出：指定引用打包 zip 到工作空间（供 /files/download 下载），返回工作空间相对路径 */
    public String archiveRef(String projectId, String ref) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String r = validateBranch(ref);
        String safeRef = r.replace('/', '-');
        String rel = "projects/" + project.getId() + "/archive/" + safeRef + ".zip";
        sandboxClient.execCommand(devWorkspaceSandbox(), "mkdir -p " + shellQuote("/workspace/projects/" + project.getId() + "/archive"), 5);
        gitExec(project, "archive --format=zip -o " + shellQuote("/workspace/" + rel) + " " + shellQuote(r), 120);
        return rel;
    }

    /** reflog 条目（误操作恢复数据源，最新在前） */
    public List<ReflogEntryDTO> reflog(String projectId) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String out = gitExec(project, "reflog -n 30 --format=%H%x1f%gd%x1f%gs", 15);
        List<ReflogEntryDTO> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\u001f", 3);
            if (parts.length < 3) {
                continue;
            }
            ReflogEntryDTO e = new ReflogEntryDTO();
            e.setSha(parts[0]);
            e.setShortSha(parts[0].length() > 7 ? parts[0].substring(0, 7) : parts[0]);
            e.setSelector(parts[1]);
            e.setSubject(parts[2]);
            list.add(e);
        }
        return list;
    }

    /** 硬重置到指定 reflog 条目（危险操作，前端二次确认；工作区未提交内容将丢失） */
    public void resetHard(String projectId, String sha) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        if (sha == null || !sha.matches("[0-9a-fA-F]{6,40}")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "非法的目标提交");
        }
        gitExec(project, "reset --hard " + shellQuote(sha), 30);
    }

    /** 双分支对比（base..head 独有提交 + 三点差异 stat） */
    public CompareDTO compareBranches(String projectId, String base, String head) {
        com.gewu.domain.project.Project project = requireGitProject(projectId);
        String b = validateBranch(base);
        String h = validateBranch(head);
        CompareDTO dto = new CompareDTO();
        dto.setBase(b);
        dto.setHead(h);
        String logOut = gitExec(project,
                "log --pretty=format:%H%x1f%an%x1f%at%x1f%s " + shellQuote(b) + ".." + shellQuote(h) + " -n 50", 20);
        for (String line : logOut.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\u001f", 4);
            if (parts.length < 4) {
                continue;
            }
            GitLogEntryDTO e = new GitLogEntryDTO();
            e.setHash(parts[0]);
            e.setShortHash(parts[0].length() > 7 ? parts[0].substring(0, 7) : parts[0]);
            e.setAuthor(parts[1]);
            try {
                e.setTimestamp(Long.parseLong(parts[2]));
            } catch (NumberFormatException ignored) {
            }
            e.setMessage(parts[3]);
            dto.getCommits().add(e);
        }
        dto.setStatText(gitExec(project, "diff --stat " + shellQuote(b) + "..." + shellQuote(h), 30));
        return dto;
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

    /** Git 主机名白名单（用于拼接 git config 键，不允许 shell 元字符） */
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._-]+(:\\d{1,5})?$");

    /** Git 分支名白名单（创建/检出/删除/合并的分支名拼入命令前必须过检） */
    private static final Pattern SAFE_BRANCH = Pattern.compile("^[A-Za-z0-9._/-]+$");

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

    static String validateGitHost(String host) {
        if (host == null || !SAFE_HOST.matcher(host).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "Git 主机名非法: " + host);
        }
        return host;
    }

    static String validateBranch(String branch) {
        if (branch == null || branch.isBlank() || !SAFE_BRANCH.matcher(branch).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "分支名非法");
        }
        return branch;
    }

    // ==================== Git 凭证 ====================
    // 凭证来源已收敛到项目配置（管理端下发，见 ProjectRepoService.resolveCredentialUrl）；
    // 用户侧增删改入口随「+ 克隆」一并移除，此处仅保留沙箱启动时的用户级凭证注入
    // （存量数据兼容，token 注入 git credential store 供匿名克隆兜底）。

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

    /**
     * 确保开发沙箱可用并运行（不存在则创建、未运行则启动），返回 sandboxId。
     * 供项目仓库按项目配置自动克隆/拉取等流程复用——用户选中项目即拉代码，
     * 不应再被"先启动沙箱"的手动步骤卡住。
     */
    public String ensureRunningSandboxId() {
        Workspace ws = getOrCreateDevWorkspace();
        if (ws.getDevSandboxId() == null) {
            ensureDevSandboxForUser(ws.getUserId());
        }
        return ensureRunningSandbox();
    }
}
