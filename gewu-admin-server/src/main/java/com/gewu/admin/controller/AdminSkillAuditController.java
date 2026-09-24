package com.gewu.admin.controller;

import com.gewu.admin.dto.skill.AuditSkillCommand;
import com.gewu.admin.dto.skill.SkillDTO;
import com.gewu.admin.service.AdminSkillAuditService;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能审核接口（管理端）：待审核列表与审核（共库直查 skill 表）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Tag(name = "技能审核", description = "后台管理端 - 技能发布审核")
public class AdminSkillAuditController {

    private final AdminSkillAuditService adminSkillAuditService;

    @GetMapping("/pending")
    @Operation(summary = "待审核技能列表", description = "管理员查询待审核的技能")
    public Result<List<SkillDTO>> pending() {
        return Result.success(adminSkillAuditService.listPendingSkills());
    }

    @PostMapping("/{skillId}/audit")
    @Operation(summary = "审核技能", description = "管理员审核待审核技能")
    public Result<Void> audit(@PathVariable String skillId, @Valid @RequestBody AuditSkillCommand command) {
        log.info("审核 Skill: skillId={}, approved={}", skillId, command.getApproved());
        adminSkillAuditService.auditSkill(skillId, command.getApproved(), command.getReason());
        return Result.success();
    }
}
