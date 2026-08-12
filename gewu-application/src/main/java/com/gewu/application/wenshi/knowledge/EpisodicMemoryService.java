package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.EpisodicEvent;
import com.gewu.infrastructure.mapper.wenshi.EpisodicEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 情景记忆服务 — 管理用户交互事件的时间序列记录与查询。
 * <p>
 * 情景记忆用于追踪用户在特定会话中发生的事件（如对话消息、操作行为），
 * 支持按时间范围、会话 ID、用户 ID 三种维度检索历史事件，
 * 为后续的上下文理解和行为分析提供数据基础。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodicMemoryService {

    private final EpisodicEventMapper mapper;

    /**
     * 记录一个情景事件。
     * <p>
     * 为事件自动生成 UUID 并持久化，适用于对话消息、用户操作等各类事件记录。
     *
     * @param tenantId  租户 ID，用于数据隔离
     * @param userId    事件关联的用户 ID
     * @param sessionId 会话 ID，标识事件所属会话
     * @param eventType 事件类型（如 MESSAGE、ACTION）
     * @param content   事件内容描述
     * @param metadata  附加元数据（JSON 字符串），可为 null
     * @return 持久化后的 {@link EpisodicEvent} 实体
     * @since 1.0.0
     */
    public EpisodicEvent record(String tenantId, String userId, String sessionId, String eventType, String content, String metadata) {
        EpisodicEvent event = new EpisodicEvent();
        event.setId(UUID.randomUUID().toString());
        event.setTenantId(tenantId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setContent(content);
        event.setMetadata(metadata);
        event.setCreatedBy(userId);
        event.setUpdatedBy(userId);

        mapper.insert(event);
        log.debug("EpisodicMemoryService.record: id={}, type={}", event.getId(), eventType);
        return event;
    }

    /**
     * 按时间范围查询情景事件。
     * <p>
     * 返回指定租户在 [startTime, endTime] 时间区间内的事件，按创建时间倒序排列。
     *
     * @param tenantId  租户 ID
     * @param startTime 起始时间戳（毫秒）
     * @param endTime   结束时间戳（毫秒）
     * @param limit     返回结果数量上限
     * @return 符合条件的事件列表，按时间倒序
     * @since 1.0.0
     */
    public List<EpisodicEvent> queryByTimeRange(String tenantId, long startTime, long endTime, int limit) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EpisodicEvent>()
                        .eq(EpisodicEvent::getTenantId, tenantId)
                        .ge(EpisodicEvent::getCreatedAt, startTime)
                        .le(EpisodicEvent::getCreatedAt, endTime)
                        .orderByDesc(EpisodicEvent::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 按会话 ID 查询情景事件。
     * <p>
     * 返回指定会话中的所有事件，按创建时间正序排列（最早的消息在前），
     * 便于还原对话上下文。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param limit     返回结果数量上限
     * @return 该会话的事件列表，按时间正序
     * @since 1.0.0
     */
    public List<EpisodicEvent> queryBySession(String tenantId, String sessionId, int limit) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EpisodicEvent>()
                        .eq(EpisodicEvent::getTenantId, tenantId)
                        .eq(EpisodicEvent::getSessionId, sessionId)
                        .orderByAsc(EpisodicEvent::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 按用户 ID 查询情景事件。
     * <p>
     * 返回指定用户的所有事件，按创建时间倒序排列（最新事件在前），
     * 用于用户行为分析和历史回溯。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param limit    返回结果数量上限
     * @return 该用户的事件列表，按时间倒序
     * @since 1.0.0
     */
    public List<EpisodicEvent> queryByUser(String tenantId, String userId, int limit) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EpisodicEvent>()
                        .eq(EpisodicEvent::getTenantId, tenantId)
                        .eq(EpisodicEvent::getUserId, userId)
                        .orderByDesc(EpisodicEvent::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }
}
