package com.gewu.application.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.project.dto.ProjectDTO;
import com.gewu.application.project.dto.ProjectMemberDTO;
import com.gewu.application.project.dto.ProjectQuery;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目应用服务 — 用户侧"我的项目"查询与成员查询.
 *
 * <p>项目创建/编辑/删除/团队分发已迁移管理端（gewu-admin-server AdminProjectService）；
 * 本服务仅保留用户侧只读查询。可见性由 project_member 成员行驱动：
 * 技术负责人（TECH_LEAD）/团队成员（MEMBER）/所有者（OWNER）均视为参与项目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;

    public ProjectDTO getProject(String projectId) {
        Project project = getProjectEntity(projectId);
        checkMembership(project); // CR-021: 确保用户是项目成员才能访问
        return toDTO(project);
    }

    public PageResult<ProjectDTO> listMyProjects(ProjectQuery query) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }

        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId));
        if (members.isEmpty()) {
            return PageResult.empty(query.getPage(), query.getSize());
        }

        List<String> projectIds = members.stream().map(ProjectMember::getProjectId).toList();
        Page<Project> page = new Page<>(query.getPage(), query.getSize());

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .in(Project::getId, projectIds);

        if (query.getProjectName() != null && !query.getProjectName().isBlank()) {
            wrapper.like(Project::getProjectName, query.getProjectName());
        }
        if (query.getProjectCode() != null && !query.getProjectCode().isBlank()) {
            wrapper.eq(Project::getProjectCode, query.getProjectCode());
        }
        if (query.getOwnerName() != null && !query.getOwnerName().isBlank()) {
            wrapper.eq(Project::getOwnerId, query.getOwnerName());
        }
        if (query.getStatus() != null) {
            wrapper.eq(Project::getStatus, query.getStatus());
        }

        wrapper.orderByDesc(Project::getCreatedAt);

        Page<Project> result = projectMapper.selectPage(page, wrapper);
        List<ProjectDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public List<ProjectMemberDTO> getProjectMembers(String projectId) {
        Project project = getProjectEntity(projectId);
        checkMembership(project); // CR-021: 确保用户是项目成员才能访问
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId));
        if (members.isEmpty()) {
            return List.of();
        }

        Set<String> userIds = members.stream().map(ProjectMember::getUserId).collect(Collectors.toSet());
        List<UserAccount> users = userAccountMapper.selectBatchIds(userIds);
        Map<String, UserAccount> userMap = users.stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));

        return members.stream()
                .map(member -> {
                    UserAccount user = userMap.get(member.getUserId());
                    return ProjectMemberDTO.builder()
                            .userId(member.getUserId())
                            .username(user != null ? user.getUsername() : null)
                            .displayName(user != null ? user.getDisplayName() : null)
                            .roleCode(member.getRoleCode())
                            .joinedAt(member.getJoinedAt())
                            .build();
                })
                .toList();
    }

    public ProjectDTO toDTO(Project project) {
        UserAccount owner = userAccountMapper.selectById(project.getOwnerId());
        Long memberCount = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, project.getId()));

        return ProjectDTO.builder()
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .projectCode(project.getProjectCode())
                .shortName(project.getShortName())
                .systemName(project.getSystemName())
                .teamMembers(project.getTeamMembers())
                .techLead(project.getTechLead())
                .description(project.getDescription())
                .visibility(project.getVisibility())
                .status(project.getStatus())
                .ownerId(project.getOwnerId())
                .ownerName(owner != null ? owner.getDisplayName() : null)
                .memberCount(memberCount)
                .createdAt(project.getCreatedAt())
                .techStack(project.getTechStack())
                .worktree(project.getWorktree())
                .iconUrl(project.getIconUrl())
                .iconColor(project.getIconColor())
                .currentPhase(project.getCurrentPhase())
                .initiatedAt(project.getInitiatedAt())
                .closedAt(project.getClosedAt())
                .repoUrl(project.getRepoUrl())
                .repoBranch(project.getRepoBranch())
                .cloneStatus(project.getCloneStatus())
                .headCommit(project.getHeadCommit())
                .repoLocalPath(project.getRepoLocalPath())
                .build();
    }

    private Project getProjectEntity(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BusinessException.of(ResultCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private void checkMembership(Project project) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        // 项目所有者或项目成员都可以访问
        if (userId.equals(project.getOwnerId())) {
            return;
        }
        Long memberCount = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, project.getId())
                        .eq(ProjectMember::getUserId, userId));
        if (memberCount == 0) {
            throw BusinessException.of(ResultCode.PERMISSION_DENIED, "您不是该项目的成员");
        }
    }
}
