package com.gewu.application.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.project.dto.CreateProjectCommand;
import com.gewu.application.project.dto.ProjectDTO;
import com.gewu.application.project.dto.ProjectMemberDTO;
import com.gewu.application.project.dto.ProjectQuery;
import com.gewu.application.project.dto.UpdateProjectCommand;
import com.gewu.application.sandbox.SandboxClient;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目应用服务 - 项目创建、查询、更新、删除及成员查询.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ProjectPhaseService projectPhaseService;
    private final SandboxClient sandboxClient;
    private final ProjectCodeGenerator projectCodeGenerator;

    @Transactional
    public ProjectDTO createProject(CreateProjectCommand command) {
        String ownerId = UserContext.currentUserId();
        if (ownerId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }

        Project project = new Project();
        project.setProjectName(command.getProjectName());
        project.setProjectCode(projectCodeGenerator.generate());
        project.setDescription(command.getDescription());
        project.setVisibility(command.getVisibility() != null ? command.getVisibility() : 0);
        project.setStatus(1);
        project.setOwnerId(ownerId);
        project.setTechStack(command.getTechStack());
        project.setWorktree(command.getWorktree());
        project.setVcs(command.getVcs());
        project.setIconUrl(command.getIconUrl());
        project.setIconColor(command.getIconColor());
        project.setRepoLocalPath("projects/" + project.getId() + "/repo");
        projectMapper.insert(project);

        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(ownerId);
        member.setRoleCode("OWNER");
        member.setJoinedAt(System.currentTimeMillis());
        projectMemberMapper.insert(member);

        projectPhaseService.initializePhases(project.getId());

        return toDTO(project);
    }

    public ProjectDTO getProject(String projectId) {
        Project project = getProjectEntity(projectId);
        checkMembership(project); // CR-021: 确保用户是项目成员才能访问
        return toDTO(project);
    }

    public PageResult<ProjectDTO> listProjects(PageQuery query) {
        Page<Project> page = new Page<>(query.getPage(), query.getSize());
        Page<Project> result = projectMapper.selectPage(page, new LambdaQueryWrapper<>());
        List<ProjectDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
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

    @Transactional
    public ProjectDTO updateProject(String projectId, UpdateProjectCommand command) {
        Project project = getProjectEntity(projectId);
        checkOwnership(project);

        if (command.getProjectName() != null) project.setProjectName(command.getProjectName());
        if (command.getDescription() != null) project.setDescription(command.getDescription());
        if (command.getVisibility() != null) project.setVisibility(command.getVisibility());
        if (command.getStatus() != null) project.setStatus(command.getStatus());
        if (command.getTechStack() != null) project.setTechStack(command.getTechStack());
        if (command.getWorktree() != null) project.setWorktree(command.getWorktree());
        if (command.getVcs() != null) project.setVcs(command.getVcs());
        if (command.getIconUrl() != null) project.setIconUrl(command.getIconUrl());
        if (command.getIconColor() != null) project.setIconColor(command.getIconColor());
        projectMapper.updateById(project);

        return toDTO(project);
    }

    @Transactional
    public void deleteProject(String projectId) {
        Project project = getProjectEntity(projectId);
        checkOwnership(project);

        destroyProjectSandboxes(projectId);

        projectMapper.deleteById(project.getId());
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

    private void destroyProjectSandboxes(String projectId) {
        try {
            sandboxClient.destroyProjectSandboxes(projectId);
            log.info("项目沙箱销毁: projectId={}", projectId);
        } catch (Exception e) {
            log.warn("项目沙箱销毁失败（不影响项目删除）: projectId={}, error={}", projectId, e.getMessage());
        }
    }

    public ProjectDTO toDTO(Project project) {
        UserAccount owner = userAccountMapper.selectById(project.getOwnerId());
        Long memberCount = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, project.getId()));

        return ProjectDTO.builder()
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .projectCode(project.getProjectCode())
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

    private void checkOwnership(Project project) {
        String userId = UserContext.currentUserId();
        if (userId == null || !userId.equals(project.getOwnerId())) {
            throw BusinessException.of(ResultCode.PERMISSION_DENIED);
        }
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