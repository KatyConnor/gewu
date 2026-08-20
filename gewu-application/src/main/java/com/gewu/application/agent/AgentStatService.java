package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.agent.AgentStatEntity;
import com.gewu.infrastructure.mapper.AgentStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Agent 统计服务 - 渐进式权限模型的数据基础。
 * <p>记录 Agent 每次执行的成功/失败，累积计算成功率，驱动信任等级自动升降。
 * <p>信任等级映射（基于累计成功率）：
 * <ul>
 *   <li>L0 仅建议 - 默认（成功率 < 80%）</li>
 *   <li>L1 小范围自动 - 单文件修改 + 只读命令（成功率 ≥ 80%）</li>
 *   <li>L2 多文件自动 - 多文件修改 + 运行测试（成功率 ≥ 85%）</li>
 *   <li>L3 大范围自主 - 多模块操作 + 部署（成功率 ≥ 90% + 人工授权）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStatService {

    private final AgentStatMapper agentStatMapper;

    private static final double L1_THRESHOLD = 80.0;
    private static final double L2_THRESHOLD = 85.0;
    private static final double L3_THRESHOLD = 90.0;

    /**
     * 记录一次执行结果。
     *
     * @param agentId Agent ID
     * @param success 是否成功
     */
    public void recordExecution(String agentId, boolean success) {
        if (agentId == null || agentId.isBlank()) return;

        AgentStatEntity stat = agentStatMapper.selectOne(
                new LambdaQueryWrapper<AgentStatEntity>().eq(AgentStatEntity::getAgentId, agentId));

        if (stat == null) {
            stat = new AgentStatEntity();
            stat.setId(Ulid.next());
            stat.setAgentId(agentId);
            stat.setTotalTasks(0);
            stat.setSuccessCount(0);
            stat.setSuccessRate(0.0);
            stat.setTrustLevel("L0");
        }

        stat.setTotalTasks(stat.getTotalTasks() + 1);
        if (success) {
            stat.setSuccessCount(stat.getSuccessCount() + 1);
        }
        stat.setSuccessRate(stat.getTotalTasks() > 0
                ? (double) stat.getSuccessCount() / stat.getTotalTasks() * 100 : 0);
        stat.setLastExecutedAt(Instant.now().toEpochMilli());

        // 自动更新信任等级
        String newTrustLevel = computeTrustLevel(stat.getSuccessRate(), stat.getTrustLevel());
        if (!newTrustLevel.equals(stat.getTrustLevel())) {
            log.info("AgentStatService 信任等级变更: agentId={}, {} -> {} (successRate={}%)",
                    agentId, stat.getTrustLevel(), newTrustLevel, stat.getSuccessRate());
        }
        stat.setTrustLevel(newTrustLevel);

        if (stat.getCreatedAt() == null) {
            agentStatMapper.insert(stat);
        } else {
            agentStatMapper.updateById(stat);
        }
    }

    /**
     * 获取 Agent 信任等级。
     */
    public String getTrustLevel(String agentId) {
        AgentStatEntity stat = agentStatMapper.selectOne(
                new LambdaQueryWrapper<AgentStatEntity>().eq(AgentStatEntity::getAgentId, agentId));
        return stat != null ? stat.getTrustLevel() : "L0";
    }

    /**
     * 获取 Agent 成功率。
     */
    public double getSuccessRate(String agentId) {
        AgentStatEntity stat = agentStatMapper.selectOne(
                new LambdaQueryWrapper<AgentStatEntity>().eq(AgentStatEntity::getAgentId, agentId));
        return stat != null ? stat.getSuccessRate() / 100.0 : 1.0;
    }

    /**
     * 检查是否应该降级。
     */
    public boolean shouldDemote(String agentId) {
        AgentStatEntity stat = agentStatMapper.selectOne(
                new LambdaQueryWrapper<AgentStatEntity>().eq(AgentStatEntity::getAgentId, agentId));
        if (stat == null) return false;
        double rate = stat.getSuccessRate();
        return switch (stat.getTrustLevel()) {
            case "L3" -> rate < L3_THRESHOLD;
            case "L2" -> rate < L2_THRESHOLD;
            case "L1" -> rate < L1_THRESHOLD;
            default -> false;
        };
    }

    /**
     * 计算信任等级。
     * <p>L3 需要人工授权，不会自动晋升到 L3（仅从 L3 不降级时保持）。
     */
    private String computeTrustLevel(double successRate, String currentLevel) {
        // L3 保持逻辑：已 L3 且 ≥90% -> 保持 L3
        if ("L3".equals(currentLevel) && successRate >= L3_THRESHOLD) {
            return "L3";
        }

        if (successRate >= L2_THRESHOLD) return "L2";
        if (successRate >= L1_THRESHOLD) return "L1";
        return "L0";
    }
}