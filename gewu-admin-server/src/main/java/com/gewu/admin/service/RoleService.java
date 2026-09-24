package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.admin.dto.role.AssignPermissionsCommand;
import com.gewu.admin.dto.role.CreateRoleCommand;
import com.gewu.admin.dto.role.PermissionDTO;
import com.gewu.admin.dto.role.RoleDTO;
import com.gewu.admin.dto.role.UpdateRoleCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.user.Permission;
import com.gewu.domain.user.Role;
import com.gewu.domain.user.RolePermission;
import com.gewu.domain.user.UserRole;
import com.gewu.infrastructure.mapper.PermissionMapper;
import com.gewu.infrastructure.mapper.RoleMapper;
import com.gewu.infrastructure.mapper.RolePermissionMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 角色管理服务 - 角色 CRUD 与权限分配.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;

    /** 角色列表（含已分配权限码与用户数） */
    public List<RoleDTO> listRoles() {
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getSortOrder));
        return roles.stream().map(this::toDTO).toList();
    }

    public RoleDTO getRole(String roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "角色不存在");
        }
        return toDTO(role);
    }

    @Transactional
    public RoleDTO createRole(CreateRoleCommand command) {
        Role existing = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, command.getRoleCode()));
        if (existing != null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "角色编码已存在");
        }
        Role role = new Role();
        role.setRoleName(command.getRoleName());
        role.setRoleCode(command.getRoleCode());
        role.setDescription(command.getDescription());
        role.setIsSystem(0);
        role.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 99);
        role.setDataScope(4); // 新建角色默认仅看本人数据
        roleMapper.insert(role);
        log.info("创建角色: id={}, code={}", role.getId(), role.getRoleCode());
        return toDTO(role);
    }

    @Transactional
    public RoleDTO updateRole(String roleId, UpdateRoleCommand command) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "角色不存在");
        }
        if (command.getRoleName() != null) role.setRoleName(command.getRoleName());
        if (command.getDescription() != null) role.setDescription(command.getDescription());
        if (command.getSortOrder() != null) role.setSortOrder(command.getSortOrder());
        if (command.getDataScope() != null) role.setDataScope(command.getDataScope());
        roleMapper.updateById(role);
        return toDTO(role);
    }

    @Transactional
    public void deleteRole(String roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "角色不存在");
        }
        if (role.getIsSystem() != null && role.getIsSystem() == 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "系统预置角色不可删除");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, roleId));
        roleMapper.deleteById(roleId);
        log.info("删除角色: id={}", roleId);
    }

    /** 获取角色已分配的权限列表 */
    public List<PermissionDTO> listRolePermissions(String roleId) {
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        if (rps.isEmpty()) return Collections.emptyList();
        List<String> permissionIds = rps.stream().map(RolePermission::getPermissionId).toList();
        return permissionMapper.selectBatchIds(permissionIds).stream()
                .map(this::toPermissionDTO).toList();
    }

    /** 分配角色权限（替换 role_permission） */
    @Transactional
    public void assignPermissions(String roleId, List<String> permissionCodes) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "角色不存在");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        if (permissionCodes != null && !permissionCodes.isEmpty()) {
            List<Permission> permissions = permissionMapper.selectList(
                    new LambdaQueryWrapper<Permission>().in(Permission::getPermissionCode, permissionCodes));
            String currentUser = UserContext.currentUserId();
            for (Permission p : permissions) {
                RolePermission rp = new RolePermission();
                rp.setId(Ulid.next());
                rp.setRoleId(roleId);
                rp.setPermissionId(p.getId());
                rp.setCreatedBy(currentUser);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("分配角色权限: roleId={}, permissions={}", roleId, permissionCodes);
    }

    private RoleDTO toDTO(Role role) {
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, role.getId()));
        List<String> permissionCodes = rps.isEmpty() ? Collections.emptyList()
                : permissionMapper.selectBatchIds(rps.stream().map(RolePermission::getPermissionId).toList())
                    .stream().map(Permission::getPermissionCode).toList();
        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, role.getId()));
        return RoleDTO.builder()
                .roleId(role.getId())
                .roleName(role.getRoleName())
                .roleCode(role.getRoleCode())
                .description(role.getDescription())
                .isSystem(role.getIsSystem())
                .sortOrder(role.getSortOrder())
                .dataScope(role.getDataScope() != null ? role.getDataScope() : 4)
                .permissionCodes(permissionCodes)
                .userCount(userCount != null ? userCount.intValue() : 0)
                .build();
    }

    private PermissionDTO toPermissionDTO(Permission p) {
        return PermissionDTO.builder()
                .permissionId(p.getId())
                .permissionCode(p.getPermissionCode())
                .permissionName(p.getPermissionName())
                .resourceType(p.getResourceType())
                .action(p.getAction())
                .description(p.getDescription())
                .build();
    }
}
