package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.AgentMarketDTO;
import com.gewu.application.agent.dto.PublishAgentCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentMarket;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentMarketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 智能体广场服务 - 上架快照、安装复制、下架.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMarketService {

    private final AgentMarketMapper agentMarketMapper;
    private final AgentMapper agentMapper;

    /** 广场列表（仅已上架审核通过，按安装次数与时间倒序） */
    public List<AgentMarketDTO> listMarket(String category) {
        LambdaQueryWrapper<AgentMarket> wrapper = new LambdaQueryWrapper<AgentMarket>()
                .eq(AgentMarket::getStatus, 2)
                .orderByDesc(AgentMarket::getInstallCount)
                .orderByDesc(AgentMarket::getCreatedAt);
        if (category != null && !category.isBlank()) {
            wrapper.eq(AgentMarket::getCategory, category);
        }
        return agentMarketMapper.selectList(wrapper).stream().map(this::toDTO).toList();
    }

    /** 发布智能体到广场（从 Agent 复制上架快照） */
    @Transactional
    public AgentMarketDTO publish(PublishAgentCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Agent agent = agentMapper.selectById(command.getAgentId());
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        // 仅创建者或管理员可发布
        if (!userId.equals(agent.getCreatedBy())) {
            List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
            if (roles == null || !roles.contains("ADMIN")) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "无权发布此 Agent");
            }
        }
        AgentMarket market = new AgentMarket();
        market.setAgentId(agent.getId());
        market.setAgentName(agent.getAgentName());
        market.setDescription(agent.getDescription());
        market.setModelProvider(agent.getModelProvider());
        market.setModelName(agent.getModelName());
        market.setModelConfig(agent.getModelConfig());
        market.setSystemPrompt(agent.getSystemPrompt());
        market.setEmoji(command.getEmoji() != null ? command.getEmoji() : "🤖");
        market.setCategory(command.getCategory());
        market.setTags(command.getTags());
        market.setStars(0);
        market.setInstallCount(0);
        market.setAuthor(UserContext.currentUsername());
        market.setStatus(1);
        market.setVersion(agent.getVersion() != null ? agent.getVersion() : 0);
        agentMarketMapper.insert(market);
        log.info("发布 Agent 到广场: agentId={}, marketId={}", agent.getId(), market.getId());
        return toDTO(market);
    }

    /** 安装广场智能体（复制快照为当前用户的 Agent） */
    @Transactional
    public void install(String marketId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        AgentMarket market = agentMarketMapper.selectById(marketId);
        if (market == null || market.getStatus() != 2) {
            throw BusinessException.of(ResultCode.AGENT_MARKET_NOT_FOUND);
        }
        Agent agent = new Agent();
        agent.setAgentName(market.getAgentName());
        agent.setDescription(market.getDescription());
        agent.setModelProvider(market.getModelProvider());
        agent.setModelName(market.getModelName());
        agent.setModelConfig(market.getModelConfig());
        agent.setSystemPrompt(market.getSystemPrompt());
        agent.setStatus(1);
        agent.setVersion(0);
        agent.setCreatedBy(userId);
        agentMapper.insert(agent);
        market.setInstallCount((market.getInstallCount() != null ? market.getInstallCount() : 0) + 1);
        agentMarketMapper.updateById(market);
        log.info("安装广场 Agent: marketId={}, newAgentId={}, userId={}", marketId, agent.getId(), userId);
    }

    /** 下架广场智能体 */
    @Transactional
    public void unpublish(String marketId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        AgentMarket market = agentMarketMapper.selectById(marketId);
        if (market == null) {
            throw BusinessException.of(ResultCode.AGENT_MARKET_NOT_FOUND);
        }
        if (!userId.equals(market.getCreatedBy())) {
            List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
            if (roles == null || !roles.contains("ADMIN")) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "无权下架此广场 Agent");
            }
        }
        market.setStatus(0);
        agentMarketMapper.updateById(market);
        log.info("下架广场 Agent: marketId={}", marketId);
    }

    /** 待审核的广场智能体列表（管理员） */
    public List<AgentMarketDTO> listPendingMarket() {
        return agentMarketMapper.selectList(
                new LambdaQueryWrapper<AgentMarket>()
                        .eq(AgentMarket::getStatus, 1)
                        .orderByDesc(AgentMarket::getCreatedAt))
                .stream().map(this::toDTO).toList();
    }

    /** 审核广场智能体上架：待审核(1) -> 已上架(2)/已拒绝(3) */
    @Transactional
    public void auditMarketPublish(String marketId, boolean approved) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
        if (roles == null || !roles.contains("ADMIN")) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权限，仅管理员可审核");
        }
        AgentMarket market = agentMarketMapper.selectById(marketId);
        if (market == null) {
            throw BusinessException.of(ResultCode.AGENT_MARKET_NOT_FOUND);
        }
        if (market.getStatus() == null || market.getStatus() != 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该广场智能体不在待审核状态");
        }
        market.setStatus(approved ? 2 : 3);
        agentMarketMapper.updateById(market);
        log.info("审核广场智能体: marketId={}, approved={}", marketId, approved);
    }

    private AgentMarketDTO toDTO(AgentMarket market) {
        return AgentMarketDTO.builder()
                .marketId(market.getId())
                .agentId(market.getAgentId())
                .agentName(market.getAgentName())
                .description(market.getDescription())
                .modelProvider(market.getModelProvider())
                .modelName(market.getModelName())
                .modelConfig(market.getModelConfig())
                .systemPrompt(market.getSystemPrompt())
                .emoji(market.getEmoji())
                .category(market.getCategory())
                .tags(market.getTags())
                .stars(market.getStars())
                .installCount(market.getInstallCount())
                .author(market.getAuthor())
                .status(market.getStatus())
                .version(market.getVersion())
                .createdAt(market.getCreatedAt())
                .createdBy(market.getCreatedBy())
                .build();
    }
}
