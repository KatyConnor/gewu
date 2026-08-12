package com.gewu.interfaceapi.controller;

import com.gewu.application.skill.SkillService;
import com.gewu.application.skill.dto.AuditSkillCommand;
import com.gewu.application.skill.dto.CreateSkillCommand;
import com.gewu.application.skill.dto.SkillDTO;
import com.gewu.application.skill.dto.UpdateSkillCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能接口 - 技能库、我的技能、安装与 CRUD.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Tag(name = "技能管理", description = "技能库、我的技能与安装")
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    @Operation(summary = "我的技能", description = "获取当前用户创建的技能列表")
    public Result<List<SkillDTO>> mySkills() {
        return Result.success(skillService.listMySkills());
    }

    @GetMapping("/library")
    @Operation(summary = "技能库", description = "获取全部技能列表")
    public Result<List<SkillDTO>> library() {
        return Result.success(skillService.listLibrary());
    }

    @PostMapping("/library/{skillId}/install")
    @Operation(summary = "安装技能", description = "复制技能到当前用户名下")
    public Result<Void> install(@PathVariable String skillId) {
        log.info("安装 Skill: skillId={}", skillId);
        skillService.installSkill(skillId);
        return Result.success();
    }

    @GetMapping("/pending")
    @Operation(summary = "待审核技能列表", description = "管理员查询待审核的技能")
    public Result<List<SkillDTO>> pending() {
        return Result.success(skillService.listPendingSkills());
    }

    @PostMapping("/{skillId}/publish")
    @Operation(summary = "发布技能", description = "用户提交自己的技能到公共库审核")
    public Result<Void> publish(@PathVariable String skillId) {
        log.info("发布 Skill: skillId={}", skillId);
        skillService.publishSkill(skillId);
        return Result.success();
    }

    @PostMapping("/{skillId}/audit")
    @Operation(summary = "审核技能", description = "管理员审核待审核技能")
    public Result<Void> audit(@PathVariable String skillId, @Valid @RequestBody AuditSkillCommand command) {
        log.info("审核 Skill: skillId={}, approved={}", skillId, command.getApproved());
        skillService.auditSkill(skillId, command.getApproved(), command.getReason());
        return Result.success();
    }

    @GetMapping("/{skillId}")
    @Operation(summary = "获取技能", description = "根据 ID 获取技能详情")
    public Result<SkillDTO> get(@PathVariable String skillId) {
        return Result.success(skillService.getSkill(skillId));
    }

    @PostMapping
    @Operation(summary = "创建技能", description = "创建新的技能")
    public Result<SkillDTO> create(@Valid @RequestBody CreateSkillCommand command) {
        log.info("创建 Skill: {}", command.getSkillName());
        return Result.success(skillService.createSkill(command));
    }

    @PutMapping("/{skillId}")
    @Operation(summary = "更新技能", description = "更新指定技能的信息")
    public Result<SkillDTO> update(@PathVariable String skillId,
                                   @Valid @RequestBody UpdateSkillCommand command) {
        log.info("更新 Skill: {}", skillId);
        return Result.success(skillService.updateSkill(skillId, command));
    }

    @DeleteMapping("/{skillId}")
    @Operation(summary = "删除技能", description = "软删除指定技能")
    public Result<Void> delete(@PathVariable String skillId) {
        log.info("删除 Skill: {}", skillId);
        skillService.deleteSkill(skillId);
        return Result.success();
    }
}
