package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.admin.dto.project.AdminProjectCreateCommand;
import com.gewu.admin.dto.project.AdminProjectDTO;
import com.gewu.admin.dto.project.AdminProjectUpdateCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.crypto.ApiKeyCryptoService;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.enums.PhaseCode;
import com.gewu.common.enums.PhaseStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.project.ProjectPhase;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.ProjectPhaseMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端项目应用服务（项目管理迁移管理端：后台为项目唯一创建/编辑入口）.
 *
 * <p>分发模型：创建时把技术负责人/团队成员落 project_member 行（TECH_LEAD/MEMBER），
 * 用户侧"我的项目"（/projects/my）按成员行过滤即实现按人分发可见。
 * project.tech_lead/team_members 姓名列仅作展示冗余，随成员行同步。
 * <p>与用户侧差异：沙箱/MinIO/仓库目录级联清理依赖用户侧运行时（SandboxClient 等），
 * 管理端删除仅做项目与成员行的逻辑删除，级联清理由后续管理端联动补齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProjectService {

    /** 项目内角色编码：所有者（创建/负责人行） */
    private static final String ROLE_OWNER = "OWNER";
    /** 项目内角色编码：技术负责人 */
    private static final String ROLE_TECH_LEAD = "TECH_LEAD";
    /** 项目内角色编码：团队成员 */
    private static final String ROLE_MEMBER = "MEMBER";

    private static final Set<Integer> VALID_STATUS = Set.of(1, 2, 3, 4, 5);

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ProjectPhaseMapper projectPhaseMapper;
    private final ProjectCodeGenerator projectCodeGenerator;
    private final ApiKeyCryptoService credentialCrypto;

    // ==================== 查询 ====================

    public PageResult<AdminProjectDTO> listProjects(PageQuery query, String keyword, Integer status) {
        Page<Project> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Project::getProjectName, kw)
                    .or().like(Project::getProjectCode, kw)
                    .or().like(Project::getSystemName, kw)
                    .or().like(Project::getTechLead, kw));
        }
        if (status != null) {
            wrapper.eq(Project::getStatus, status);
        }
        wrapper.orderByDesc(Project::getCreatedAt);

        Page<Project> result = projectMapper.selectPage(page, wrapper);
        List<AdminProjectDTO> dtos = toDTOs(result.getRecords());
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public AdminProjectDTO getProject(String projectId) {
        return toDTO(getEntity(projectId));
    }

    /** 项目成员视图（需求分派选择器数据源：分派对象必须是项目成员） */
    public record ProjectMemberView(String userId, String displayName, String roleCode) {}

    /** 项目成员列表（含 OWNER/TECH_LEAD/MEMBER 全部角色） */
    public List<ProjectMemberView> getProjectMembers(String projectId) {
        getEntity(projectId);
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId));
        if (members.isEmpty()) return List.of();
        Set<String> userIds = members.stream().map(ProjectMember::getUserId).collect(Collectors.toSet());
        Map<String, UserAccount> userMap = userAccountMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));
        return members.stream()
                .map(m -> {
                    UserAccount u = userMap.get(m.getUserId());
                    return new ProjectMemberView(m.getUserId(), u != null ? u.getDisplayName() : null, m.getRoleCode());
                })
                .toList();
    }

    // ==================== 创建（含团队分发） ====================

    @Transactional
    public AdminProjectDTO createProject(AdminProjectCreateCommand command) {
        String adminId = UserContext.currentUserId();
        if (adminId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }

        UserAccount lead = resolveEnabledUser(command.getTechLeadUserId());
        List<UserAccount> members = resolveEnabledUsers(command.getMemberUserIds(), lead);

        Project project = new Project();
        // 显式生成 ULID：repoLocalPath 在 insert 前拼 ID（同用户侧 createProject）
        project.setId(Ulid.next());
        project.setProjectName(command.getProjectName().trim());
        project.setProjectCode(projectCodeGenerator.generate());
        project.setShortName(command.getShortName());
        project.setSystemName(command.getSystemName());
        project.setTechLead(lead != null ? lead.getDisplayName() : null);
        project.setTeamMembers(members.stream().map(UserAccount::getDisplayName).collect(Collectors.joining(", ")));
        project.setDescription(command.getDescription());
        project.setVisibility(command.getVisibility() != null ? command.getVisibility() : 0);
        project.setStatus(1);
        // 所有者 = 技术负责人（未指定时回落到创建管理员），保证 checkOwnership 语义成立
        project.setOwnerId(lead != null ? lead.getId() : adminId);
        project.setTechStack(command.getTechStack());
        project.setWorktree(command.getWorktree());
        project.setVcs(command.getVcs());
        project.setIconUrl(command.getIconUrl());
        project.setIconColor(command.getIconColor());
        project.setRepoUrl(command.getRepoUrl());
        applyRepoCredential(project, command.getRepoCredentialType(), command.getRepoUsername(), command.getRepoCredential());
        project.setRepoLocalPath("projects/" + project.getId() + "/repo");
        projectMapper.insert(project);

        insertMember(project.getId(), project.getOwnerId(), ROLE_OWNER);
        if (lead != null) {
            insertMember(project.getId(), lead.getId(), ROLE_TECH_LEAD);
        }
        members.forEach(m -> insertMember(project.getId(), m.getId(), ROLE_MEMBER));

        initializePhases(project.getId());

        return toDTO(project);
    }

    // ==================== 更新（含团队重分配） ====================

    @Transactional
    public AdminProjectDTO updateProject(String projectId, AdminProjectUpdateCommand command) {
        Project project = getEntity(projectId);

        if (command.getProjectName() != null && !command.getProjectName().isBlank()) {
            project.setProjectName(command.getProjectName().trim());
        }
        if (command.getShortName() != null) project.setShortName(command.getShortName());
        if (command.getSystemName() != null) project.setSystemName(command.getSystemName());
        if (command.getDescription() != null) project.setDescription(command.getDescription());
        if (command.getVisibility() != null) project.setVisibility(command.getVisibility());
        if (command.getStatus() != null) {
            if (!VALID_STATUS.contains(command.getStatus())) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "项目状态不合法: " + command.getStatus());
            }
            project.setStatus(command.getStatus());
        }
        if (command.getTechStack() != null) project.setTechStack(command.getTechStack());
        if (command.getVcs() != null) project.setVcs(command.getVcs());
        if (command.getWorktree() != null) project.setWorktree(command.getWorktree());
        if (command.getIconUrl() != null) project.setIconUrl(command.getIconUrl());
        if (command.getIconColor() != null) project.setIconColor(command.getIconColor());
        if (command.getRepoUrl() != null) project.setRepoUrl(command.getRepoUrl());
        if (command.getRepoCredential() != null && !command.getRepoCredential().isBlank()) {
            applyRepoCredential(project, command.getRepoCredentialType(), command.getRepoUsername(), command.getRepoCredential());
        }

        // 团队重分配：任一字段出现即视为整队调整（未出现的一方沿用现有成员行）
        if (command.getTechLeadUserId() != null || command.getMemberUserIds() != null) {
            syncTeam(project, command);
        }

        projectMapper.updateById(project);
        return toDTO(project);
    }

    /**
     * 团队整队同步：保留 OWNER 行，替换 TECH_LEAD/MEMBER 行。
     * 负责人变更同步 ownerId（保持"所有者=技术负责人"不变量；清除负责人时所有者不动）。
     */
    private void syncTeam(Project project, AdminProjectUpdateCommand command) {
        Map<String, ProjectMember> current = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, project.getId()))
                .stream()
                .collect(Collectors.toMap(ProjectMember::getUserId, Function.identity(), (a, b) -> a));

        UserAccount newLead;
        if (command.getTechLeadUserId() == null) {
            String currentLeadId = current.values().stream()
                    .filter(m -> ROLE_TECH_LEAD.equals(m.getRoleCode()))
                    .map(ProjectMember::getUserId).findFirst().orElse(null);
            newLead = resolveEnabledUser(currentLeadId);
        } else {
            newLead = resolveEnabledUser(command.getTechLeadUserId().isBlank() ? null : command.getTechLeadUserId());
        }
        List<UserAccount> newMembers;
        if (command.getMemberUserIds() == null) {
            newMembers = current.values().stream()
                    .filter(m -> ROLE_MEMBER.equals(m.getRoleCode()))
                    .map(m -> userAccountMapper.selectById(m.getUserId()))
                    .filter(u -> u != null && u.getStatus() != null && u.getStatus() == 1)
                    .toList();
        } else {
            newMembers = resolveEnabledUsers(command.getMemberUserIds(), newLead);
        }

        // 逻辑删除旧技术角色行 + 物理清理历史删除行（防 uk_project_member 撞键）后重建
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId())
                .in(ProjectMember::getRoleCode, ROLE_TECH_LEAD, ROLE_MEMBER));
        projectMemberMapper.purgeDeletedByProjectId(project.getId());

        if (newLead != null) {
            insertMember(project.getId(), newLead.getId(), ROLE_TECH_LEAD);
            project.setTechLead(newLead.getDisplayName());
            project.setOwnerId(newLead.getId());
        } else {
            project.setTechLead(null);
        }
        project.setTeamMembers(newMembers.stream().map(UserAccount::getDisplayName).collect(Collectors.joining(", ")));
        newMembers.forEach(m -> insertMember(project.getId(), m.getId(), ROLE_MEMBER));
    }

    // ==================== 删除 ====================

    /**
     * 逻辑删除项目与全部成员行（@TableLogic）。
     * 沙箱/MinIO 产物/仓库目录级联清理不在管理端执行（依赖用户侧运行时），见类注释。
     */
    @Transactional
    public void deleteProject(String projectId) {
        Project project = getEntity(projectId);
        projectMapper.deleteById(project.getId());
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId()));
        log.info("管理端删除项目(逻辑删除): projectId={}, name={}", projectId, project.getProjectName());
    }

    // ==================== 私有辅助 ====================

    private void insertMember(String projectId, String userId, String roleCode) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRoleCode(roleCode);
        member.setJoinedAt(System.currentTimeMillis());
        projectMemberMapper.insert(member);
    }

    /** 初始化 17 个生命周期阶段（与用户侧 createProject 行为一致） */
    private void initializePhases(String projectId) {
        for (PhaseCode pc : PhaseCode.values()) {
            ProjectPhase phase = new ProjectPhase();
            phase.setProjectId(projectId);
            phase.setPhaseCode(pc.name());
            phase.setPhaseOrder(pc.getOrder());
            phase.setStatus(PhaseStatus.NOT_STARTED.getCode());
            projectPhaseMapper.insert(phase);
        }
    }

    /**
     * 仓库凭证二选一校验与加密落库（与用户侧 ProjectService.applyRepoCredential 同规则）：
     * 访问凭证（token/ssh_key）与用户名密码（password）只能选其一；明文 SM4 加密存 repo_credential。
     */
    private void applyRepoCredential(Project project, String type, String username, String credential) {
        if (credential == null || credential.isBlank()) {
            if (username != null && !username.isBlank()) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "已填写仓库用户名，请同时填写密码");
            }
            return;
        }
        if (username != null && !username.isBlank() && !"password".equals(type)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "访问凭证与用户名密码二选一，请勿同时填写");
        }
        String resolvedType = type;
        if (resolvedType == null || resolvedType.isBlank()) {
            resolvedType = credential.strip().startsWith("-----BEGIN") ? "ssh_key" : "token";
        }
        if (!Set.of("token", "ssh_key", "password").contains(resolvedType)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仓库凭证类型不合法: " + resolvedType);
        }
        if ("password".equals(resolvedType) && (username == null || username.isBlank())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "用户名密码认证需同时填写用户名与密码");
        }
        project.setRepoCredentialType(resolvedType);
        project.setRepoUsername(username);
        project.setRepoCredential(credentialCrypto.encrypt(credential.strip()));
    }

    /** 按ID解析启用用户；id 为 null 返回 null，不存在/禁用抛参数异常 */
    private UserAccount resolveEnabledUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "用户不存在: " + userId);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "用户已禁用: " + user.getDisplayName());
        }
        return user;
    }

    /** 批量解析启用用户：去重、剔除负责人本人、保持传入顺序 */
    private List<UserAccount> resolveEnabledUsers(List<String> userIds, UserAccount lead) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Map<String, UserAccount> unique = new LinkedHashMap<>();
        for (String id : userIds) {
            if (id == null || id.isBlank() || unique.containsKey(id)) continue;
            if (lead != null && id.equals(lead.getId())) continue;
            UserAccount user = resolveEnabledUser(id);
            unique.put(id, user);
        }
        return new ArrayList<>(unique.values());
    }

    private Project getEntity(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private List<AdminProjectDTO> toDTOs(List<Project> projects) {
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        Set<String> ownerIds = projects.stream().map(Project::getOwnerId).collect(Collectors.toSet());
        Map<String, UserAccount> ownerMap = ownerIds.isEmpty() ? Map.of()
                : userAccountMapper.selectBatchIds(ownerIds).stream()
                        .collect(Collectors.toMap(UserAccount::getId, u -> u));
        Set<String> projectIds = projects.stream().map(Project::getId).collect(Collectors.toSet());
        Map<String, List<ProjectMember>> memberMap = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>().in(ProjectMember::getProjectId, projectIds))
                .stream().collect(Collectors.groupingBy(ProjectMember::getProjectId));

        return projects.stream()
                .map(p -> toDTO(p, ownerMap.get(p.getOwnerId()), memberMap.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private AdminProjectDTO toDTO(Project project) {
        return toDTO(project, userAccountMapper.selectById(project.getOwnerId()),
                projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, project.getId())));
    }

    private AdminProjectDTO toDTO(Project project, UserAccount owner, List<ProjectMember> members) {
        String leadUserId = members.stream()
                .filter(m -> ROLE_TECH_LEAD.equals(m.getRoleCode()))
                .map(ProjectMember::getUserId).findFirst().orElse(null);
        List<String> memberUserIds = members.stream()
                .filter(m -> ROLE_MEMBER.equals(m.getRoleCode()))
                .map(ProjectMember::getUserId).toList();

        return AdminProjectDTO.builder()
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .projectCode(project.getProjectCode())
                .shortName(project.getShortName())
                .systemName(project.getSystemName())
                .techLead(project.getTechLead())
                .teamMembers(project.getTeamMembers())
                .techLeadUserId(leadUserId)
                .memberUserIds(memberUserIds)
                .description(project.getDescription())
                .visibility(project.getVisibility())
                .status(project.getStatus())
                .ownerId(project.getOwnerId())
                .ownerName(owner != null ? owner.getDisplayName() : null)
                .memberCount((long) members.size())
                .createdAt(project.getCreatedAt())
                .techStack(project.getTechStack())
                .worktree(project.getWorktree())
                .vcs(project.getVcs())
                .iconUrl(project.getIconUrl())
                .iconColor(project.getIconColor())
                .repoUrl(project.getRepoUrl())
                .repoBranch(project.getRepoBranch())
                .cloneStatus(project.getCloneStatus())
                .headCommit(project.getHeadCommit())
                .repoLocalPath(project.getRepoLocalPath())
                .currentPhase(project.getCurrentPhase())
                .initiatedAt(project.getInitiatedAt())
                .closedAt(project.getClosedAt())
                .build();
    }
}
