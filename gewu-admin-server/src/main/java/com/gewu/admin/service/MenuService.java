package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.admin.dto.menu.CreateMenuCommand;
import com.gewu.admin.dto.menu.MenuDTO;
import com.gewu.admin.dto.menu.UpdateMenuCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.menu.Menu;
import com.gewu.domain.menu.RoleMenu;
import com.gewu.domain.user.UserRole;
import com.gewu.infrastructure.mapper.MenuMapper;
import com.gewu.infrastructure.mapper.RoleMenuMapper;
import com.gewu.infrastructure.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单管理服务 - 菜单树构建、CRUD、角色菜单分配.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final UserRoleMapper userRoleMapper;

    /** 全量菜单树（管理员视角，含隐藏菜单） */
    public List<MenuDTO> listMenuTree() {
        List<Menu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder));
        return buildTree(menus);
    }

    /**
     * 当前用户可见菜单树.
     * <p>ADMIN 返回全部可见菜单；其他角色按 role_menu 关联 + 权限码过滤.
     */
    public List<MenuDTO> listCurrentUserMenus() {
        UserContext ctx = UserContext.get();
        if (ctx == null || ctx.getUserId() == null) {
            return Collections.emptyList();
        }

        boolean isAdmin = ctx.getRoleCodes() != null && ctx.getRoleCodes().contains("ADMIN");

        List<Menu> menus;
        if (isAdmin) {
            // ADMIN 超权：全部可见菜单
            menus = menuMapper.selectList(
                    new LambdaQueryWrapper<Menu>()
                            .eq(Menu::getVisible, 1)
                            .orderByAsc(Menu::getSortOrder));
        } else {
            // 非 ADMIN：按 role_menu 关联查询
            List<UserRole> userRoles = userRoleMapper.selectList(
                    new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, ctx.getUserId()));
            if (userRoles.isEmpty()) return Collections.emptyList();

            List<String> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
            List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                    new LambdaQueryWrapper<RoleMenu>().in(RoleMenu::getRoleId, roleIds));
            if (roleMenus.isEmpty()) return Collections.emptyList();

            List<String> menuIds = roleMenus.stream().map(RoleMenu::getMenuId).distinct().toList();
            menus = menuMapper.selectList(
                    new LambdaQueryWrapper<Menu>()
                            .in(Menu::getId, menuIds)
                            .eq(Menu::getVisible, 1)
                            .orderByAsc(Menu::getSortOrder));

            // 权限码过滤：permission_code 为空或用户拥有该权限
            Set<String> permissions = ctx.getPermissions() != null ? ctx.getPermissions() : Collections.emptySet();
            menus = menus.stream()
                    .filter(m -> m.getPermissionCode() == null || m.getPermissionCode().isBlank()
                            || permissions.contains(m.getPermissionCode()))
                    .toList();
        }

        return buildTreeWithPrune(menus);
    }

    @Transactional
    public MenuDTO createMenu(CreateMenuCommand command) {
        if (command.getParentId() != null && !command.getParentId().isBlank()) {
            Menu parent = menuMapper.selectById(command.getParentId());
            if (parent == null) {
                throw BusinessException.of(ResultCode.NOT_FOUND, "父菜单不存在");
            }
        }
        Menu menu = new Menu();
        menu.setParentId(command.getParentId());
        menu.setMenuName(command.getMenuName());
        menu.setMenuType(command.getMenuType() != null ? command.getMenuType() : 2);
        menu.setPath(command.getPath());
        menu.setIcon(command.getIcon());
        menu.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 99);
        menu.setPermissionCode(command.getPermissionCode());
        menu.setVisible(command.getVisible() != null ? command.getVisible() : 1);
        menuMapper.insert(menu);
        log.info("创建菜单: id={}, name={}", menu.getId(), menu.getMenuName());
        return toDTO(menu);
    }

    @Transactional
    public MenuDTO updateMenu(String menuId, UpdateMenuCommand command) {
        Menu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "菜单不存在");
        }
        if (command.getParentId() != null) menu.setParentId(command.getParentId().isBlank() ? null : command.getParentId());
        if (command.getMenuName() != null) menu.setMenuName(command.getMenuName());
        if (command.getMenuType() != null) menu.setMenuType(command.getMenuType());
        if (command.getPath() != null) menu.setPath(command.getPath().isBlank() ? null : command.getPath());
        if (command.getIcon() != null) menu.setIcon(command.getIcon().isBlank() ? null : command.getIcon());
        if (command.getSortOrder() != null) menu.setSortOrder(command.getSortOrder());
        if (command.getPermissionCode() != null) menu.setPermissionCode(command.getPermissionCode().isBlank() ? null : command.getPermissionCode());
        if (command.getVisible() != null) menu.setVisible(command.getVisible());
        menuMapper.updateById(menu);
        return toDTO(menu);
    }

    @Transactional
    public void deleteMenu(String menuId) {
        Menu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "菜单不存在");
        }
        // 检查是否有子菜单
        Long childCount = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, menuId));
        if (childCount > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "存在子菜单，无法删除");
        }
        // 清理 role_menu 关联
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getMenuId, menuId));
        menuMapper.deleteById(menuId);
        log.info("删除菜单: id={}", menuId);
    }

    /** 获取角色已分配的菜单ID列表 */
    public List<String> listRoleMenuIds(String roleId) {
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRoleId, roleId));
        return roleMenus.stream().map(RoleMenu::getMenuId).toList();
    }

    /** 分配角色菜单（替换 role_menu） */
    @Transactional
    public void assignRoleMenus(String roleId, List<String> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRoleId, roleId));
        if (menuIds != null && !menuIds.isEmpty()) {
            String currentUser = UserContext.currentUserId();
            for (String menuId : menuIds) {
                RoleMenu rm = new RoleMenu();
                rm.setId(Ulid.next());
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                rm.setCreatedBy(currentUser);
                roleMenuMapper.insert(rm);
            }
        }
        log.info("分配角色菜单: roleId={}, menuCount={}", roleId, menuIds != null ? menuIds.size() : 0);
    }

    // ==================== 私有方法 ====================

    /** 构建菜单树（全量，不剪枝） */
    private List<MenuDTO> buildTree(List<Menu> menus) {
        Map<String, List<Menu>> byParent = menus.stream()
                .collect(Collectors.groupingBy(m -> m.getParentId() != null ? m.getParentId() : "ROOT"));
        return buildChildren(byParent, "ROOT");
    }

    private List<MenuDTO> buildChildren(Map<String, List<Menu>> byParent, String parentKey) {
        List<Menu> children = byParent.get(parentKey);
        if (children == null || children.isEmpty()) return Collections.emptyList();
        return children.stream()
                .map(m -> {
                    MenuDTO dto = toDTO(m);
                    dto.setChildren(buildChildren(byParent, m.getId()));
                    return dto;
                })
                .toList();
    }

    /**
     * 构建菜单树并剪枝：移除没有子节点的目录（menu_type=1）.
     * <p>非 ADMIN 用户经过权限码过滤后，部分目录可能没有可见子菜单，需要移除。
     */
    private List<MenuDTO> buildTreeWithPrune(List<Menu> menus) {
        Map<String, List<Menu>> byParent = menus.stream()
                .collect(Collectors.groupingBy(m -> m.getParentId() != null ? m.getParentId() : "ROOT"));
        return buildChildrenWithPrune(byParent, "ROOT");
    }

    private List<MenuDTO> buildChildrenWithPrune(Map<String, List<Menu>> byParent, String parentKey) {
        List<Menu> children = byParent.get(parentKey);
        if (children == null || children.isEmpty()) return Collections.emptyList();
        List<MenuDTO> result = new ArrayList<>();
        for (Menu m : children) {
            MenuDTO dto = toDTO(m);
            List<MenuDTO> subChildren = buildChildrenWithPrune(byParent, m.getId());
            // 目录类型：无子菜单则跳过（剪枝）；菜单/按钮类型：始终保留
            if (m.getMenuType() != null && m.getMenuType() == 1 && subChildren.isEmpty()) {
                continue;
            }
            dto.setChildren(subChildren);
            result.add(dto);
        }
        return result;
    }

    private MenuDTO toDTO(Menu menu) {
        return MenuDTO.builder()
                .menuId(menu.getId())
                .parentId(menu.getParentId())
                .menuName(menu.getMenuName())
                .menuType(menu.getMenuType())
                .path(menu.getPath())
                .icon(menu.getIcon())
                .sortOrder(menu.getSortOrder())
                .permissionCode(menu.getPermissionCode())
                .visible(menu.getVisible())
                .children(Collections.emptyList())
                .build();
    }
}
