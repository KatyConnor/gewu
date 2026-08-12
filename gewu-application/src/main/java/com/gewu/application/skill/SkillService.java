package com.gewu.application.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.skill.dto.CreateSkillCommand;
import com.gewu.application.skill.dto.SkillDTO;
import com.gewu.application.skill.dto.UpdateSkillCommand;
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
 * 技能服务 - 技能库、我的技能、安装复制与 CRUD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillMapper skillMapper;

    /** 技能库（仅已发布审核通过的公共技能） */
    public List<SkillDTO> listLibrary() {
        List<Skill> list = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getPublishStatus, 2)
                        .orderByDesc(Skill::getCreatedAt));
        return list.stream().map(this::toDTO).toList();
    }

    /** 我的技能（当前用户创建，按时间倒序） */
    public List<SkillDTO> listMySkills() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        List<Skill> list = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getCreatedBy, userId)
                        .orderByDesc(Skill::getCreatedAt));
        return list.stream().map(this::toDTO).toList();
    }

    @Transactional
    public SkillDTO createSkill(CreateSkillCommand command) {
        Skill skill = new Skill();
        skill.setSkillName(command.getSkillName());
        skill.setDescription(command.getDescription());
        skill.setCategory(command.getCategory());
        skill.setContent(command.getContent());
        skill.setEmoji(command.getEmoji() != null ? command.getEmoji() : "⚡");
        skill.setTags(command.getTags());
        skill.setInstallCount(0);
        skill.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        skill.setVersion(0);
        skill.setPublishStatus(0);
        skillMapper.insert(skill);
        log.info("创建 Skill: id={}, name={}", skill.getId(), skill.getSkillName());
        return toDTO(skill);
    }

    public SkillDTO getSkill(String skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        return toDTO(skill);
    }

    @Transactional
    public SkillDTO updateSkill(String skillId, UpdateSkillCommand command) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        if (command.getSkillName() != null) skill.setSkillName(command.getSkillName());
        if (command.getDescription() != null) skill.setDescription(command.getDescription());
        if (command.getCategory() != null) skill.setCategory(command.getCategory());
        if (command.getContent() != null) skill.setContent(command.getContent());
        if (command.getEmoji() != null) skill.setEmoji(command.getEmoji());
        if (command.getTags() != null) skill.setTags(command.getTags());
        if (command.getStatus() != null) skill.setStatus(command.getStatus());
        skillMapper.updateById(skill);
        return toDTO(skill);
    }

    @Transactional
    public void deleteSkill(String skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        skillMapper.deleteById(skillId);
        log.info("删除 Skill: id={}", skillId);
    }

    /** 安装技能（复制到当前用户名下，原技能 install_count+1） */
    @Transactional
    public void installSkill(String skillId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Skill source = skillMapper.selectById(skillId);
        if (source == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        Skill copy = new Skill();
        copy.setSkillName(source.getSkillName());
        copy.setDescription(source.getDescription());
        copy.setCategory(source.getCategory());
        copy.setContent(source.getContent());
        copy.setEmoji(source.getEmoji());
        copy.setTags(source.getTags());
        copy.setInstallCount(0);
        copy.setStatus(1);
        copy.setVersion(0);
        copy.setPublishStatus(0);
        skillMapper.insert(copy);
        source.setInstallCount((source.getInstallCount() != null ? source.getInstallCount() : 0) + 1);
        skillMapper.updateById(source);
        log.info("安装 Skill: sourceId={}, newId={}, userId={}", skillId, copy.getId(), userId);
    }

    /** 用户发布自己的技能（提交审核）：私有/已拒绝 -> 待审核 */
    @Transactional
    public void publishSkill(String skillId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw BusinessException.of(ResultCode.SKILL_NOT_FOUND);
        }
        checkOwnership(skill, userId);
        Integer ps = skill.getPublishStatus();
        if (ps != null && ps == 2) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "技能已发布，无需重复发布");
        }
        if (ps != null && ps == 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "技能正在审核中");
        }
        skill.setPublishStatus(1);
        skillMapper.updateById(skill);
        log.info("技能提交审核: id={}, user={}", skillId, userId);
    }

    /** 管理员审核技能：待审核 -> 已发布/已拒绝 */
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

    /** 管理员查询待审核技能列表 */
    public List<SkillDTO> listPendingSkills() {
        requireAdmin();
        List<Skill> list = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getPublishStatus, 1)
                        .orderByDesc(Skill::getCreatedAt));
        return list.stream().map(this::toDTO).toList();
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

    private void checkOwnership(Skill skill, String userId) {
        if (!userId.equals(skill.getCreatedBy())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权操作此技能");
        }
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
}
