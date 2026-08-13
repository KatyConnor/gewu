package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.FailureCaseEntity;
import com.gewu.infrastructure.mapper.FailureCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 失败案例服务 - 管理失败案例的记录、检索与复用。
 * <p>任务失败后自动触发记录；下次类似任务检索复用，实现可控反思闭环。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureCaseService {

    private final FailureCaseMapper failureCaseMapper;

    /**
     * 记录失败案例。
     */
    public FailureCaseEntity record(String taskType, String contextSummary, String failurePoint,
                                    String rootCause, String lesson, String avoidanceRule,
                                    String agentId, String executionId) {
        FailureCaseEntity entity = new FailureCaseEntity();
        entity.setId(Ulid.next());
        entity.setTaskType(taskType);
        entity.setContextSummary(truncate(contextSummary, 500));
        entity.setFailurePoint(truncate(failurePoint, 256));
        entity.setRootCause(truncate(rootCause, 1000));
        entity.setLesson(truncate(lesson, 1000));
        entity.setAvoidanceRule(truncate(avoidanceRule, 1000));
        entity.setConfidence(0.5f);
        entity.setOccurrenceCount(1);
        entity.setAgentId(agentId);
        entity.setExecutionId(executionId);
        failureCaseMapper.insert(entity);
        log.info("记录失败案例: id={}, taskType={}, failurePoint={}", entity.getId(), taskType, failurePoint);
        return entity;
    }

    /**
     * 按任务类型检索相似失败案例。
     */
    public List<FailureCaseEntity> searchByTaskType(String taskType, int limit) {
        return failureCaseMapper.selectList(
                new LambdaQueryWrapper<FailureCaseEntity>()
                        .eq(FailureCaseEntity::getTaskType, taskType)
                        .orderByDesc(FailureCaseEntity::getOccurrenceCount)
                        .orderByDesc(FailureCaseEntity::getCreatedAt)
                        .last("LIMIT " + limit));
    }

    /**
     * 检索所有失败案例（用于规划前规避提示）。
     */
    public List<FailureCaseEntity> searchByAgent(String agentId, int limit) {
        return failureCaseMapper.selectList(
                new LambdaQueryWrapper<FailureCaseEntity>()
                        .eq(FailureCaseEntity::getAgentId, agentId)
                        .orderByDesc(FailureCaseEntity::getOccurrenceCount)
                        .last("LIMIT " + limit));
    }

    /**
     * 递增出现次数（相似失败重复出现时）。
     */
    public void incrementOccurrence(String caseId) {
        FailureCaseEntity entity = failureCaseMapper.selectById(caseId);
        if (entity != null) {
            entity.setOccurrenceCount((entity.getOccurrenceCount() != null ? entity.getOccurrenceCount() : 0) + 1);
            failureCaseMapper.updateById(entity);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}