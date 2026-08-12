package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.agent.dto.AgentDTO;
import com.gewu.application.agent.dto.AgentToolDTO;
import com.gewu.application.agent.dto.CreateAgentCommand;
import com.gewu.application.agent.dto.UpdateAgentCommand;
import com.gewu.application.skill.dto.SkillDTO;
import com.gewu.common.annotation.DataPermission;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentExecution;
import com.gewu.domain.agent.AgentExecutionCount;
import com.gewu.domain.agent.AgentTool;
import com.gewu.domain.agent.AgentSkill;
import com.gewu.domain.skill.Skill;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentSkillMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;
    private final SessionMapper sessionMapper;

    @Transactional
    public AgentDTO createAgent(CreateAgentCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        
        Agent agent = new Agent();
        agent.setAgentName(command.getAgentName());
        agent.setDescription(command.getDescription());
        agent.setModelProvider(command.getModelProvider());
        agent.setModelName(command.getModelName());
        agent.setModelConfig(command.getModelConfig());
        agent.setSystemPrompt(command.getSystemPrompt());
        agent.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        agent.setVersion(0);
        agent.setCreatedBy(userId); // 设置创建者
        agentMapper.insert(agent);
        return toDTO(agent, 0);
    }

    public AgentDTO getAgent(String agentId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        
        // 权限检查：只有创建者可以访问（或管理员）
        if (!userId.equals(agent.getCreatedBy())) {
            // 检查是否为管理员角色
            List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
            if (roles == null || !roles.contains("ADMIN")) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "无权访问此 Agent");
            }
        }
        
        Map<String, Integer> counts = countConversations(List.of(agent.getId()));
        return toDTO(agent, counts.getOrDefault(agent.getId(), 0));
    }

    @DataPermission(orgField = "created_by")
    public PageResult<AgentDTO> listAgents(PageQuery query) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }

        Page<Agent> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>();
        wrapper.orderByDesc(Agent::getCreatedAt);

        Page<Agent> result = agentMapper.selectPage(page, wrapper);

        // 批量统计每个 Agent 的对话次数（agent_execution），避免 N+1 查询
        Map<String, Integer> conversationCounts = countConversations(
                result.getRecords().stream().map(Agent::getId).toList());

        List<AgentDTO> dtos = result.getRecords().stream()
                .map(a -> toDTO(a, conversationCounts.getOrDefault(a.getId(), 0)))
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    /** 按 agent_id 批量统计执行（对话）次数 */
    private Map<String, Integer> countConversations(List<String> agentIds) {
        Map<String, Integer> counts = new HashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return counts;
        }
        sessionMapper.countByAgentIds(agentIds)
                .forEach(c -> counts.put(c.getAgentId(), c.getCnt() != null ? c.getCnt() : 0));
        return counts;
    }

    @Transactional
    public AgentDTO updateAgent(String agentId, UpdateAgentCommand command) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);

        if (command.getAgentName() != null) agent.setAgentName(command.getAgentName());
        if (command.getDescription() != null) agent.setDescription(command.getDescription());
        if (command.getModelProvider() != null) agent.setModelProvider(command.getModelProvider());
        if (command.getModelName() != null) agent.setModelName(command.getModelName());
        if (command.getModelConfig() != null) agent.setModelConfig(command.getModelConfig());
        if (command.getSystemPrompt() != null) agent.setSystemPrompt(command.getSystemPrompt());
        if (command.getStatus() != null) agent.setStatus(command.getStatus());
        agentMapper.updateById(agent);

        return toDTO(agent, 0);
    }

    @Transactional
    public void deleteAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);
        agentMapper.deleteById(agentId);
    }

    public List<AgentToolDTO> getAgentTools(String agentId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        
        // 权限检查：只有创建者或管理员可以访问
        if (!userId.equals(agent.getCreatedBy())) {
            List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
            if (roles == null || !roles.contains("ADMIN")) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "无权访问此 Agent 工具");
            }
        }
        
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .orderByAsc(AgentTool::getSortOrder));
        return tools.stream().map(AgentService::toToolDTO).toList();
    }

    /** 获取智能体已挂载的技能列表 */
    public List<SkillDTO> listAgentSkills(String agentId) {
        List<AgentSkill> agentSkills = agentSkillMapper.selectList(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .orderByAsc(AgentSkill::getSortOrder));
        if (agentSkills.isEmpty()) return List.of();
        List<String> skillIds = agentSkills.stream().map(AgentSkill::getSkillId).toList();
        Map<String, Skill> skillMap = skillMapper.selectBatchIds(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        return agentSkills.stream()
                .map(as -> skillMap.get(as.getSkillId()))
                .filter(java.util.Objects::nonNull)
                .map(this::toSkillDTO)
                .toList();
    }

    /** 挂载技能到智能体 */
    @Transactional
    public void mountSkill(String agentId, String skillId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);
        Long exists = agentSkillMapper.selectCount(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .eq(AgentSkill::getSkillId, skillId));
        if (exists != null && exists > 0) {
            return;
        }
        AgentSkill as = new AgentSkill();
        as.setAgentId(agentId);
        as.setSkillId(skillId);
        as.setSortOrder(0);
        agentSkillMapper.insert(as);
    }

    /** 卸载智能体的技能 */
    @Transactional
    public void unmountSkill(String agentId, String skillId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);
        agentSkillMapper.delete(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getAgentId, agentId)
                .eq(AgentSkill::getSkillId, skillId));
    }

    private SkillDTO toSkillDTO(Skill skill) {
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
                .createdAt(skill.getCreatedAt())
                .createdBy(skill.getCreatedBy())
                .build();
    }

    private void checkOwnership(Agent agent) {
        String currentUserId = UserContext.currentUserId();
        if (currentUserId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        if (!currentUserId.equals(agent.getCreatedBy())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权操作此 Agent");
        }
    }

    private AgentDTO toDTO(Agent agent, int conversations) {
        return AgentDTO.builder()
                .agentId(agent.getId())
                .agentName(agent.getAgentName())
                .description(agent.getDescription())
                .modelProvider(agent.getModelProvider())
                .modelName(agent.getModelName())
                .modelConfig(agent.getModelConfig())
                .systemPrompt(agent.getSystemPrompt())
                .status(agent.getStatus())
                .statusDesc(resolveStatusDesc(agent.getStatus()))
                .version(agent.getVersion())
                .createdAt(agent.getCreatedAt())
                .createdBy(agent.getCreatedBy())
                .conversations(conversations)
                .build();
    }

    private static AgentToolDTO toToolDTO(AgentTool tool) {
        return AgentToolDTO.builder()
                .toolId(tool.getId())
                .agentId(tool.getAgentId())
                .toolName(tool.getToolName())
                .description(tool.getDescription())
                .toolType(tool.getToolType())
                .endpoint(tool.getEndpoint())
                .timeoutMs(tool.getTimeoutMs())
                .status(tool.getStatus())
                .sortOrder(tool.getSortOrder())
                .build();
    }

    private String resolveStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 1 -> "启用";
            case 0 -> "禁用";
            default -> "未知";
        };
    }
}
