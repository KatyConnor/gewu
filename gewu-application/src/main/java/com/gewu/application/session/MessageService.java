package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.session.dto.*;
import com.gewu.application.sse.SseEventManager;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMessage;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息应用服务 — 会话消息的发送、查询、编辑、删除与搜索.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final SessionMessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final UserAccountMapper userMapper;
    private final ObjectMapper objectMapper;
    private final SseEventManager sseEventManager;
    private final SessionMessageAppender messageAppender;

    @Transactional
    public MessageDTO sendMessage(String sessionId, SendMessageCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }

        // clientId 幂等：同一次发送动作重复请求（网络重试）直接返回已落库消息
        SessionMessage existing = messageAppender.findByIdempotentKey(sessionId, command.getClientId());
        if (existing != null) {
            return toDTO(existing, getSenderName(existing.getSenderId()));
        }

        SessionMessage message = new SessionMessage();
        message.setSessionId(sessionId);
        message.setSenderId(userId);
        message.setMessageType(command.getMessageType() != null ? command.getMessageType() : "text");
        message.setContent(command.getContent());
        message.setReplyTo(command.getReplyTo());
        if (command.getMentionUserIds() != null && !command.getMentionUserIds().isEmpty()) {
            message.setMentionUserIds(toJson(command.getMentionUserIds()));
        }
        message.setClientId(command.getClientId());
        message.setEdited(0);
        // seq 冲突重试 + 原子计数统一由 Appender 承担
        messageAppender.appendWithRetry(message);

        MessageDTO messageDTO = toDTO(message, getSenderName(userId));
        sseEventManager.sendEvent(sessionId, "message", messageDTO);
        return messageDTO;
    }

    public PageResult<MessageDTO> listMessages(String sessionId, PageQuery query) {
        return listMessages(sessionId, query, "asc");
    }

    /**
     * 会话消息历史查询。
     *
     * @param order "asc"（默认，兼容旧行为）取最旧一页升序返回；
     *              "desc" 取<b>最新一页</b>并按时间升序返回——修复长会话（>100 条）
     *              固定取第 1 页导致最新消息被截断的问题
     */
    public PageResult<MessageDTO> listMessages(String sessionId, PageQuery query, String order) {
        boolean latestWindow = "desc".equalsIgnoreCase(order);
        Page<SessionMessage> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<SessionMessage> wrapper = new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId);
        // 最新窗口按 seq 倒序取尾部（seq 会话内单调，比 createdAt 毫秒更可靠），再反转回正序
        wrapper.orderBy(latestWindow, false, SessionMessage::getSeq);
        Page<SessionMessage> result = messageMapper.selectPage(page, wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(query.getPage(), query.getSize());
        }
        List<SessionMessage> records = new java.util.ArrayList<>(result.getRecords());
        if (latestWindow) {
            java.util.Collections.reverse(records);
        }
        Map<String, String> senderNames = getSenderNames(records);
        List<MessageDTO> dtos = records.stream()
                .map(m -> toDTO(m, senderNames.get(m.getSenderId())))
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public MessageDTO getMessage(String messageId) {
        SessionMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw BusinessException.of(ResultCode.MESSAGE_NOT_FOUND);
        }
        return toDTO(message, getSenderName(message.getSenderId()));
    }

    @Transactional
    public MessageDTO editMessage(String messageId, String content) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        SessionMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw BusinessException.of(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (!userId.equals(message.getSenderId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能编辑自己的消息");
        }
        message.setContent(content);
        message.setEdited(1);
        messageMapper.updateById(message);
        return toDTO(message, getSenderName(userId));
    }

    @Transactional
    public void deleteMessage(String messageId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        SessionMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw BusinessException.of(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (!userId.equals(message.getSenderId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能删除自己的消息");
        }
        messageMapper.deleteById(messageId);
        // 逻辑删除后同步扣减会话消息计数，保持侧栏消息数与物理行数一致
        sessionMapper.decrementMessageCount(message.getSessionId(), 1);
    }

    public PageResult<MessageDTO> searchMessages(String sessionId, String keyword, PageQuery query) {
        Page<SessionMessage> page = new Page<>(query.getPage(), query.getSize());
        Page<SessionMessage> result = messageMapper.selectPage(page,
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .like(SessionMessage::getContent, keyword)
                        .orderByAsc(SessionMessage::getCreatedAt));
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(query.getPage(), query.getSize());
        }
        Map<String, String> senderNames = getSenderNames(result.getRecords());
        List<MessageDTO> dtos = result.getRecords().stream()
                .map(m -> toDTO(m, senderNames.get(m.getSenderId())))
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "消息序列化失败");
        }
    }

    private Map<String, String> getSenderNames(List<SessionMessage> messages) {
        Set<String> senderIds = messages.stream()
                .map(SessionMessage::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (senderIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(senderIds).stream()
                .collect(Collectors.toMap(
                        UserAccount::getId,
                        u -> u.getDisplayName() != null ? u.getDisplayName() : u.getUsername()));
    }

    private String getSenderName(String senderId) {
        if (senderId == null) return null;
        UserAccount user = userMapper.selectById(senderId);
        return user != null ? (user.getDisplayName() != null ? user.getDisplayName() : user.getUsername()) : null;
    }

    private MessageDTO toDTO(SessionMessage message, String senderName) {
        return MessageDTO.builder()
                .messageId(message.getId())
                .sessionId(message.getSessionId())
                .senderId(message.getSenderId())
                .senderName(senderName)
                .messageType(message.getMessageType())
                .content(message.getContent())
                .replyTo(message.getReplyTo())
                .seq(message.getSeq())
                .edited(message.getEdited())
                .createdAt(message.getCreatedAt())
                .metadata(message.getMetadata())
                .build();
    }
}
