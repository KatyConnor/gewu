package com.gewu.application.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.session.SessionService;
import com.gewu.application.session.dto.SessionDTO;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.domain.agent.AgentStatEntity;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.infrastructure.mapper.AgentStatMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务 - 仪表盘与用量页的数据聚合。
 * <p>聚合 Agent 执行统计（成功率/信任等级）、编排执行记录
 * （次数/状态/token 消耗）与最近会话，供前端 Dashboard/Usage 页消费。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final AgentStatMapper agentStatMapper;
    private final OrchestrationExecutionMapper executionMapper;
    private final SessionService sessionService;
    private final com.gewu.infrastructure.mapper.AgentExecutionMapper agentExecutionMapper;

    // ==================== 仪表盘 ====================

    /**
     * 仪表盘聚合数据。
     */
    public DashboardStats dashboard() {
        DashboardStats stats = new DashboardStats();

        // Agent 执行统计
        List<AgentStatEntity> agentStats = agentStatMapper.selectList(null);
        stats.agentTracked = agentStats.size();
        stats.avgSuccessRate = agentStats.stream()
                .filter(s -> s.getSuccessRate() != null)
                .mapToDouble(AgentStatEntity::getSuccessRate)
                .average().orElse(0);
        Map<String, Integer> trust = new HashMap<>();
        agentStats.forEach(s -> trust.merge(s.getTrustLevel() != null ? s.getTrustLevel() : "L0", 1, Integer::sum));
        stats.trustDistribution = trust;

        // 编排执行统计
        List<OrchestrationExecutionEntity> executions = executionMapper.selectList(null);
        stats.executionTotal = executions.size();
        stats.executionSucceeded = (int) executions.stream().filter(e -> "SUCCEEDED".equals(e.getStatus())).count();
        stats.executionFailed = (int) executions.stream().filter(e -> "FAILED".equals(e.getStatus())).count();
        stats.executionRunning = (int) executions.stream()
                .filter(e -> "RUNNING".equals(e.getStatus()) || "PAUSED".equals(e.getStatus())).count();

        // 最近会话
        try {
            PageResult<SessionDTO> sessions = sessionService.listSessions(new PageQuery());
            stats.recentSessions = sessions.getRecords();
        } catch (Exception e) {
            log.debug("最近会话查询失败: {}", e.getMessage());
        }
        return stats;
    }

    // ==================== 用量统计 ====================

    /**
     * 用量聚合数据：token 消耗、成本与近期执行。
     */
    public UsageStats usage() {
        UsageStats stats = new UsageStats();
        List<OrchestrationExecutionEntity> executions = executionMapper.selectList(
                new LambdaQueryWrapper<OrchestrationExecutionEntity>()
                        .orderByDesc(OrchestrationExecutionEntity::getCreatedAt)
                        .last("LIMIT 500"));

        stats.totalTokenUsed = executions.stream()
                .mapToLong(e -> e.getTokenUsed() != null ? e.getTokenUsed() : 0).sum();
        stats.totalCost = executions.stream()
                .mapToDouble(e -> e.getCostConsumed() != null ? e.getCostConsumed().doubleValue() : 0).sum();
        Map<String, Long> byStatus = new HashMap<>();
        executions.forEach(e -> byStatus.merge(e.getStatus(), 1L, Long::sum));

        // 对话执行（agent_execution）与编排执行合并统计（T4.1：agent 账本已自动落库）
        try {
            List<com.gewu.domain.agent.AgentExecution> agentExecutions = agentExecutionMapper.selectList(
                    new LambdaQueryWrapper<com.gewu.domain.agent.AgentExecution>()
                            .orderByDesc(com.gewu.domain.agent.AgentExecution::getCreatedAt)
                            .last("LIMIT 500"));
            stats.totalTokenUsed += agentExecutions.stream()
                    .mapToLong(e -> e.getTokensUsed() != null ? e.getTokensUsed() : 0).sum();
            agentExecutions.forEach(e -> byStatus.merge(e.getStatus(), 1L, Long::sum));
            stats.chatExecutionTotal = agentExecutions.size();
        } catch (Exception e) {
            log.debug("对话执行统计查询失败（忽略）: {}", e.getMessage());
        }

        stats.byStatus = byStatus;
        stats.recentExecutions = executions.stream()
                .limit(20)
                .map(e -> {
                    RecentExecution re = new RecentExecution();
                    re.executionId = e.getId();
                    re.graphId = e.getGraphId();
                    re.status = e.getStatus();
                    re.tokenUsed = e.getTokenUsed();
                    re.costConsumed = e.getCostConsumed();
                    re.startedAt = e.getStartedAt();
                    re.completedAt = e.getCompletedAt();
                    return re;
                })
                .toList();
        return stats;
    }

    // ===== DTO =====

    @Data
    public static class DashboardStats {
        /** 有执行记录的 Agent 数 */
        public int agentTracked;
        /** 平均成功率（0-100） */
        public double avgSuccessRate;
        /** 信任等级分布 L0-L3 -> count */
        public Map<String, Integer> trustDistribution;
        public long executionTotal;
        public long executionSucceeded;
        public long executionFailed;
        public long executionRunning;
        /** 最近会话（最多 5 条） */
        public List<SessionDTO> recentSessions;
    }

    @Data
    public static class UsageStats {
        public long totalTokenUsed;
        public double totalCost;
        /** 状态分布 -> count */
        public Map<String, Long> byStatus;
        /** 对话执行（agent_execution）样本量 */
        public long chatExecutionTotal;
        public List<RecentExecution> recentExecutions;
    }

    @Data
    public static class RecentExecution {
        public String executionId;
        public String graphId;
        public String status;
        public Long tokenUsed;
        public java.math.BigDecimal costConsumed;
        public Long startedAt;
        public Long completedAt;
    }
}
