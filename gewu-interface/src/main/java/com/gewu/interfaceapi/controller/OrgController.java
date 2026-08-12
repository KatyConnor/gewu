package com.gewu.interfaceapi.controller;

import com.gewu.application.org.OrgService;
import com.gewu.application.org.dto.CreateOrgCommand;
import com.gewu.application.org.dto.OrgDTO;
import com.gewu.application.org.dto.UpdateOrgCommand;
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
 * 机构管理接口 - 组织树查询与 CRUD.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('org:manage')")
@Tag(name = "机构管理", description = "组织机构树与管理")
public class OrgController {

    private final OrgService orgService;

    @GetMapping
    @Operation(summary = "机构树", description = "获取全部机构树（含用户数）")
    public Result<List<OrgDTO>> list() {
        return Result.success(orgService.listOrgTree());
    }

    @PostMapping
    @Operation(summary = "创建机构")
    public Result<OrgDTO> create(@Valid @RequestBody CreateOrgCommand command) {
        log.info("创建机构: {}", command.getOrgCode());
        return Result.success(orgService.createOrg(command));
    }

    @PutMapping("/{orgId}")
    @Operation(summary = "更新机构")
    public Result<OrgDTO> update(@PathVariable String orgId, @Valid @RequestBody UpdateOrgCommand command) {
        return Result.success(orgService.updateOrg(orgId, command));
    }

    @DeleteMapping("/{orgId}")
    @Operation(summary = "删除机构", description = "存在子机构或用户时无法删除")
    public Result<Void> delete(@PathVariable String orgId) {
        log.info("删除机构: {}", orgId);
        orgService.deleteOrg(orgId);
        return Result.success();
    }
}
