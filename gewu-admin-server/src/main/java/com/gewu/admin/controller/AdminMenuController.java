package com.gewu.admin.controller;

import com.gewu.admin.service.MenuService;
import com.gewu.admin.dto.menu.AssignMenusCommand;
import com.gewu.admin.dto.menu.CreateMenuCommand;
import com.gewu.admin.dto.menu.MenuDTO;
import com.gewu.admin.dto.menu.UpdateMenuCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单管理接口 - 菜单树查询、CRUD、角色菜单分配.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/menus")
@RequiredArgsConstructor
@Tag(name = "菜单管理", description = "菜单动态权限与角色菜单分配")
public class AdminMenuController {

    private final MenuService menuService;


    @GetMapping
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "全量菜单树", description = "获取全部菜单树（含隐藏），需 menu:manage 权限")
    public Result<List<MenuDTO>> list() {
        return Result.success(menuService.listMenuTree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "创建菜单")
    public Result<MenuDTO> create(@Valid @RequestBody CreateMenuCommand command) {
        log.info("创建菜单: {}", command.getMenuName());
        return Result.success(menuService.createMenu(command));
    }

    @PutMapping("/{menuId}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "更新菜单")
    public Result<MenuDTO> update(@PathVariable String menuId, @Valid @RequestBody UpdateMenuCommand command) {
        return Result.success(menuService.updateMenu(menuId, command));
    }

    @DeleteMapping("/{menuId}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "删除菜单", description = "存在子菜单时无法删除")
    public Result<Void> delete(@PathVariable String menuId) {
        log.info("删除菜单: {}", menuId);
        menuService.deleteMenu(menuId);
        return Result.success();
    }

    @GetMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "角色已分配菜单ID列表")
    public Result<List<String>> listRoleMenuIds(@PathVariable String roleId) {
        return Result.success(menuService.listRoleMenuIds(roleId));
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Operation(summary = "分配角色菜单", description = "替换角色的菜单集合")
    public Result<Void> assignRoleMenus(@PathVariable String roleId,
                                         @Valid @RequestBody AssignMenusCommand command) {
        log.info("分配角色菜单: roleId={}", roleId);
        menuService.assignRoleMenus(roleId, command.getMenuIds());
        return Result.success();
    }
}
