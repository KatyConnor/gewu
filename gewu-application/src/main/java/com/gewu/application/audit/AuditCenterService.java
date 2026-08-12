package com.gewu.application.audit;

import com.gewu.application.agent.AgentMarketService;
import com.gewu.application.agent.dto.AgentMarketDTO;
import com.gewu.application.audit.dto.PendingAuditDTO;
import com.gewu.application.skill.SkillService;
import com.gewu.application.skill.dto.SkillDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批中心服务 - 聚合各业务审批待办，统一通过/拒绝分发.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCenterService {

    private final SkillService skillService;
    private final AgentMarketService agentMarketService;

    /** 聚合待审核列表（技能发布 + 智能体广场上架） */
    public List<PendingAuditDTO> listPendingAudits() {
        requireAdmin();
        List<PendingAuditDTO> list = new ArrayList<>();
        for (SkillDTO s : skillService.listPendingSkills()) {
            list.add(PendingAuditDTO.builder()
                    .auditType("SKILL_PUBLISH").targetId(s.getSkillId()).name(s.getSkillName())
                    .description(s.getDescription()).content(s.getContent()).category(s.getCategory())
                    .emoji(s.getEmoji()).createdAt(s.getCreatedAt()).createdBy(s.getCreatedBy()).build());
        }
        for (AgentMarketDTO m : agentMarketService.listPendingMarket()) {
            list.add(PendingAuditDTO.builder()
                    .auditType("AGENT_MARKET").targetId(m.getMarketId()).name(m.getAgentName())
                    .description(m.getDescription()).content(m.getSystemPrompt()).category(m.getCategory())
                    .emoji(m.getEmoji()).createdAt(m.getCreatedAt()).createdBy(m.getCreatedBy()).build());
        }
        return list;
    }

    /** 审批通过 */
    public void approve(String auditType, String targetId) {
        requireAdmin();
        switch (auditType) {
            case "SKILL_PUBLISH" -> skillService.auditSkill(targetId, true, null);
            case "AGENT_MARKET" -> agentMarketService.auditMarketPublish(targetId, true);
            default -> throw BusinessException.of(ResultCode.PARAM_INVALID, "未知审批类型: " + auditType);
        }
        log.info("审批通过: type={}, id={}", auditType, targetId);
    }

    /** 审批拒绝 */
    public void reject(String auditType, String targetId, String reason) {
        requireAdmin();
        switch (auditType) {
            case "SKILL_PUBLISH" -> skillService.auditSkill(targetId, false, reason);
            case "AGENT_MARKET" -> agentMarketService.auditMarketPublish(targetId, false);
            default -> throw BusinessException.of(ResultCode.PARAM_INVALID, "未知审批类型: " + auditType);
        }
        log.info("审批拒绝: type={}, id={}, reason={}", auditType, targetId, reason);
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
}
