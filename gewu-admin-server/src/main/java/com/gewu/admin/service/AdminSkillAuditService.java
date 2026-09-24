package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.admin.dto.skill.SkillDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.skill.Skill;
import com.gewu.infrastructure.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 技能审核服务（管理端，共库直查 skill 表）：待审核列表 + 审核。
 * 审核写入逻辑与主应用 SkillService.auditSkill 同口径（publishStatus: 1待审→2通过/3拒绝）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSkillAuditService {

    private final SkillMapper skillMapper;

    /** 管理员查询待审核技能列表 */
    public List<SkillDTO> listPendingSkills() {
        requireAdmin();
        List<Skill> list = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getPublishStatus, 1)
                        .orderByDesc(Skill::getCreatedAt));
        return list.stream().map(this::toDTO).toList();
    }

    /** 管理员审核技能 */
    @Transactional
    public void auditSkill(String skillId, boolean approved, String reason) {
        requireAdmin();
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        if (skill.getPublishStatus() == null || skill.getPublishStatus() != 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该技能不在待审核状态");
        }
        skill.setPublishStatus(approved ? 2 : 3);
        skillMapper.updateById(skill);
        log.info("技能审核: id={}, approved={}, reason={}", skillId, approved, reason);
    }

    private void requireAdmin() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
        if (roles == null || !roles.contains("ADMIN")) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权限，仅管理员可操作");
        }
    }

    private SkillDTO toDTO(Skill skill) {
        return SkillDTO.builder()
                .skillId(skill.getId())
                .skillName(skill.getSkillName())
                .description(skill.getDescription())
                .category(skill.getCategory())
                .content(skill.getContent())
                .emoji(skill.getEmoji())
                .tags(skill.getTags())
                .installCount(skill.getInstallCount())
                .status(skill.getStatus())
                .version(skill.getVersion())
                .publishStatus(skill.getPublishStatus())
                .publishStatusDesc(resolvePublishStatusDesc(skill.getPublishStatus()))
                .createdAt(skill.getCreatedAt())
                .createdBy(skill.getCreatedBy())
                .build();
    }

    private String resolvePublishStatusDesc(Integer publishStatus) {
        if (publishStatus == null) return "私有";
        return switch (publishStatus) {
            case 0 -> "私有";
            case 1 -> "待审核";
            case 2 -> "已发布";
            case 3 -> "已拒绝";
            default -> "未知";
        };
    }
}
