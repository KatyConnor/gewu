package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.admin.dto.role.CreatePermissionCommand;
import com.gewu.admin.dto.role.PermissionDTO;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.Permission;
import com.gewu.domain.user.RolePermission;
import com.gewu.infrastructure.mapper.PermissionMapper;
import com.gewu.infrastructure.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权限管理服务 - 权限定义的查询与维护.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    /** 权限列表（按资源类型排序） */
    public List<PermissionDTO> listPermissions() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getResourceType))
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public PermissionDTO createPermission(CreatePermissionCommand command) {
        Permission existing = permissionMapper.selectOne(
                new LambdaQueryWrapper<Permission>().eq(Permission::getPermissionCode, command.getPermissionCode()));
        if (existing != null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "权限编码已存在");
        }
        Permission p = new Permission();
        p.setPermissionCode(command.getPermissionCode());
        p.setPermissionName(command.getPermissionName());
        p.setResourceType(command.getResourceType());
        p.setAction(command.getAction());
        p.setDescription(command.getDescription());
        permissionMapper.insert(p);
        log.info("创建权限: code={}", p.getPermissionCode());
        return toDTO(p);
    }

    @Transactional
    public void deletePermission(String permissionId) {
        // 清理角色权限关联
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, permissionId));
        permissionMapper.deleteById(permissionId);
        log.info("删除权限: id={}", permissionId);
    }

    private PermissionDTO toDTO(Permission p) {
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
