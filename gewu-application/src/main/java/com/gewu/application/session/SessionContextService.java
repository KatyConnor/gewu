package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.gewu.application.session.dto.MessageDTO;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.cache.CacheKeys;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionContextService {

    private static final int DEFAULT_MAX_MESSAGES = 50;
    private static final int TOKEN_THRESHOLD = 4000;
    private static final Duration CACHE_TTL = Duration.ofHours(2);

    private final SessionMessageMapper sessionMessageMapper;
    private final CacheService cacheService;
    private final ContextCompressor contextCompressor;

    public List<MessageDTO> getContext(String sessionId, int maxMessages) {
        int limit = maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES;
        List<SessionMessage> messages = sessionMessageMapper.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .orderByDesc(SessionMessage::getCreatedAt)
                        .last("LIMIT " + limit));
        return messages.reversed().stream()
                .map(this::toMessageDTO)
                .toList();
    }

    @Transactional
    public void compressContext(String sessionId) {
        List<SessionMessage> messages = sessionMessageMapper.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .orderByAsc(SessionMessage::getCreatedAt));

        int estimatedTokens = messages.stream()
                .mapToInt(m -> estimateTokens(m.getContent()))
                .sum();

        if (estimatedTokens <= TOKEN_THRESHOLD) {
            return;
        }

        List<ContextCompressor.MessageView> views = messages.stream()
                .map(m -> new ContextCompressor.MessageView(
                        resolveRole(m.getSenderId()),
                        m.getContent()))
                .toList();

        String compressed = contextCompressor.compress(views);
        String cacheKey = CacheKeys.messages(sessionId) + ":compressed";
        cacheService.set(cacheKey, compressed, CACHE_TTL);
    }

    @Transactional
    public void addToContext(String sessionId, MessageDTO message) {
        SessionMessage entity = new SessionMessage();
        entity.setSessionId(sessionId);
        entity.setSenderId(message.getSenderId());
        entity.setMessageType(message.getMessageType());
        entity.setContent(message.getContent());
        entity.setSeq(message.getSeq());
        entity.setEdited(0);
        sessionMessageMapper.insert(entity);

        String cacheKey = CacheKeys.messages(sessionId);
        cacheService.delete(cacheKey);
    }

    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.length() / 4;
    }

    private String resolveRole(String senderId) {
        return senderId != null && senderId.startsWith("agent") ? "assistant" : "user";
    }

    private MessageDTO toMessageDTO(SessionMessage message) {
        return MessageDTO.builder()
                .messageId(message.getId())
                .sessionId(message.getSessionId())
                .senderId(message.getSenderId())
                .messageType(message.getMessageType())
                .content(message.getContent())
                .replyTo(message.getReplyTo())
                .seq(message.getSeq())
                .edited(message.getEdited())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
