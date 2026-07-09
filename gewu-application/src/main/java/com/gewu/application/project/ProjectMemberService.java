package com.gewu.application.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.project.dto.AddMemberCommand;
import com.gewu.application.project.dto.ProjectMemberDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目成员应用服务 — 成员的添加、移除、角色更新与成员关系校验.
 */
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;

    @Transactional
    public ProjectMemberDTO addMember(String projectId, AddMemberCommand command) {
        UserAccount user = userAccountMapper.selectById(command.getUserId());
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }

        Long existing = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, command.getUserId()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.PROJECT_ALREADY_EXISTS, "用户已是项目成员");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(command.getUserId());
        member.setRoleCode(command.getRoleCode());
        member.setJoinedAt(System.currentTimeMillis());
        projectMemberMapper.insert(member);

        return ProjectMemberDTO.builder()
                .userId(member.getUserId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .roleCode(member.getRoleCode())
                .joinedAt(member.getJoinedAt())
                .build();
    }

    @Transactional
    public void removeMember(String projectId, String userId) {
        int deleted = projectMemberMapper.delete(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId));
        if (deleted == 0) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目成员不存在");
        }
    }

    @Transactional
    public void updateMemberRole(String projectId, String userId, String roleCode) {
        ProjectMember member = projectMemberMapper.selectOne(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId));
        if (member == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "项目成员不存在");
        }
        member.setRoleCode(roleCode);
        projectMemberMapper.updateById(member);
    }

    public boolean isMember(String projectId, String userId) {
        Long count = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId));
        return count > 0;
    }
}