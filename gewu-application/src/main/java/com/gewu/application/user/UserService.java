package com.gewu.application.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.user.dto.UpdateUserCommand;
import com.gewu.application.user.dto.UserDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.crypto.PasswordHasher;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.UserAccount;
import com.gewu.domain.user.UserRole;
import com.gewu.domain.org.Organization;
import com.gewu.infrastructure.mapper.OrganizationMapper;
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
    private final OrganizationMapper organizationMapper;
    private final com.gewu.application.auth.AuthService authService;

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

    public PageResult<UserDTO> listUsers(PageQuery query, String keyword, String orgId) {
        Page<UserAccount> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(UserAccount::getUsername, keyword)
                    .or().like(UserAccount::getEmail, keyword)
                    .or().like(UserAccount::getDisplayName, keyword));
        }
        if (orgId != null && !orgId.isBlank()) {
            wrapper.eq(UserAccount::getOrgId, orgId);
        }
        Page<UserAccount> result = userMapper.selectPage(page, wrapper);

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

    /** 管理员更新用户状态（1=启用 2=禁用 3=锁定） */
    @Transactional
    public void updateUserStatus(String userId, Integer status) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        user.setStatus(status);
        if (status != null && status == 1) {
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
        }
        userMapper.updateById(user);
    }

    /** 管理员重置用户密码 */
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        String hash = PasswordHasher.hash(newPassword);
        user.setPasswordHash(hash);
        user.setPasswordSalt(hash.split("\\$")[1]);
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);
    }

    /** 管理员分配角色（替换 user_role） */
    @Transactional
    public void assignRoles(String userId, List<String> roleCodes) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (roleCodes != null && !roleCodes.isEmpty()) {
            List<Role> roles = roleMapper.selectList(
                    new LambdaQueryWrapper<Role>().in(Role::getRoleCode, roleCodes));
            for (Role role : roles) {
                UserRole ur = new UserRole();
                ur.setId(Ulid.next());
                ur.setUserId(userId);
                ur.setRoleId(role.getId());
                ur.setSource("admin");
                userRoleMapper.insert(ur);
            }
        }
    }

    /** 管理员分配用户机构 */
    @Transactional
    public void assignOrg(String userId, String orgId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ResultCode.USER_NOT_FOUND);
        }
        if (orgId != null && !orgId.isBlank()) {
            Organization org = organizationMapper.selectById(orgId);
            if (org == null) {
                throw BusinessException.of(ResultCode.NOT_FOUND, "机构不存在");
            }
            user.setOrgId(orgId);
        } else {
            user.setOrgId(null);
        }
        userMapper.updateById(user);
    }

    private UserDTO toDTO(UserAccount user) {
        List<String> roleCodes = getRoleCodes(user.getId());
        java.util.List<String> permissions = new java.util.ArrayList<>(authService.getPermissionsByUserId(user.getId()));
        String orgName = null;
        if (user.getOrgId() != null) {
            Organization org = organizationMapper.selectById(user.getOrgId());
            if (org != null) orgName = org.getOrgName();
        }
        return UserDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .orgId(user.getOrgId())
                .orgName(orgName)
                .roleCodes(roleCodes)
                .permissions(permissions)
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