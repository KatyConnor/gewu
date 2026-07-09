package com.gewu.application.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.user.dto.UpdateUserCommand;
import com.gewu.application.user.dto.UserDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.UserAccount;
import com.gewu.domain.user.UserRole;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户应用服务 — 用户信息查询与更新.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public UserDTO getCurrentUser() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        return getUserById(userId);
    }

    public UserDTO getUserById(String userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        return toDTO(user);
    }

    public PageResult<UserDTO> listUsers(PageQuery query) {
        Page<UserAccount> page = new Page<>(query.getPage(), query.getSize());
        Page<UserAccount> result = userMapper.selectPage(page, new LambdaQueryWrapper<>());

        List<UserDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public UserDTO updateUser(UpdateUserCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }

        if (command.getDisplayName() != null) user.setDisplayName(command.getDisplayName());
        if (command.getAvatarUrl() != null) user.setAvatarUrl(command.getAvatarUrl());
        if (command.getPhone() != null) user.setPhone(command.getPhone());
        userMapper.updateById(user);

        return toDTO(user);
    }

    private UserDTO toDTO(UserAccount user) {
        List<String> roleCodes = getRoleCodes(user.getId());
        return UserDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .roleCodes(roleCodes)
                .build();
    }

    private List<String> getRoleCodes(String userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return List.of();
        }
        List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(role -> role.getRoleCode())
                .collect(Collectors.toList());
    }
}