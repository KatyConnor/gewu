package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.gewu.application.session.dto.MessageDTO;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.cache.CacheKeys;
import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gewu.infrastructure.llm.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionContextService {

    private static final int DEFAULT_MAX_MESSAGES = 50;
    private static final int TOKEN_THRESHOLD = 4000;
    private static final int COMPRESSED_KEEP_RECENT = 6;
    private static final Duration CACHE_TTL = Duration.ofHours(2);

    private final SessionMessageMapper sessionMessageMapper;
    private final SessionMapper sessionMapper;
    private final CacheService cacheService;
    private final ContextCompressor contextCompressor;
    private final SessionMessageAppender messageAppender;
    private final SessionTitleService titleService;

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

    public List<Message> buildContextMessages(String sessionId, int maxMessages) {
        int limit = maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES;
        List<SessionMessage> messages = sessionMessageMapper.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .orderByDesc(SessionMessage::getSeq)
                        .last("LIMIT " + limit));
        messages = messages.reversed();

        if (messages.isEmpty()) {
            return new ArrayList<>();
        }

        int estimatedTokens = messages.stream()
                .mapToInt(m -> estimateTokens(m.getContent()))
                .sum();

        if (estimatedTokens <= TOKEN_THRESHOLD) {
            return messages.stream()
                    .map(this::toLlmMessage)
                    .toList();
        }

        log.info("会话上下文超过 token 阈值，触发压缩: sessionId={}, estimatedTokens={}", sessionId, estimatedTokens);
        
        // CR-013: 改进的压缩策略 - 保留 system 消息和最近消息
        List<SessionMessage> systemMessages = new ArrayList<>();
        List<SessionMessage> regularMessages = new ArrayList<>();
        
        // 分离 system 消息和普通消息
        for (SessionMessage msg : messages) {
            if ("system".equals(msg.getMessageType())) {
                systemMessages.add(msg);
            } else {
                regularMessages.add(msg);
            }
        }
        
        // 保留最近的普通消息
        int splitIdx = Math.max(0, regularMessages.size() - COMPRESSED_KEEP_RECENT);
        List<SessionMessage> oldMessages = regularMessages.subList(0, splitIdx);
        List<SessionMessage> recentMessages = regularMessages.subList(splitIdx, regularMessages.size());
        
        // 压缩旧消息
        String compressed = "";
        if (!oldMessages.isEmpty()) {
            List<ContextCompressor.MessageView> views = oldMessages.stream()
                    .map(m -> new ContextCompressor.MessageView(
                            resolveRole(m.getSenderId()),
                            m.getContent()))
                    .toList();
            compressed = contextCompressor.compress(views);
        }

        // 构建结果：system 消息 + 压缩摘要 + 最近消息
        List<Message> result = new ArrayList<>();
        
        // 1. 保留所有 system 消息
        for (SessionMessage sm : systemMessages) {
            result.add(Message.builder()
                    .role("system")
                    .content(sm.getContent())
                    .build());
        }
        
        // 2. 添加压缩摘要（如果有）
        if (!compressed.isEmpty()) {
            result.add(Message.builder()
                    .role("system")
                    .content("[对话历史摘要]\n" + compressed)
                    .build());
        }
        
        // 3. 添加最近的普通消息
        for (SessionMessage sm : recentMessages) {
            result.add(toLlmMessage(sm));
        }

        // 缓存压缩结果
        if (!compressed.isEmpty()) {
            String cacheKey = CacheKeys.messages(sessionId) + ":compressed";
            cacheService.set(cacheKey, compressed, CACHE_TTL);
        }

        return result;
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

    @Transactional
    public void appendChatInteraction(String sessionId, String userId, String userContent,
                                      String assistantContent, String clientId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.warn("会话不存在，跳过持久化: sessionId={}", sessionId);
            return;
        }
        // clientId 幂等：流式接口重放/网络重试时避免重复落库与重复计费
        if (messageAppender.findByIdempotentKey(sessionId, clientId) != null) {
            log.info("AI 交互已落库，幂等跳过: sessionId={}, clientId={}", sessionId, clientId);
            return;
        }

        int inserted = 0;
        boolean firstInteraction = false;
        if (userContent != null && !userContent.isBlank()) {
            SessionMessage userMsg = new SessionMessage();
            userMsg.setSessionId(sessionId);
            userMsg.setSenderId(userId);
            userMsg.setMessageType("user");
            userMsg.setContent(userContent);
            userMsg.setClientId(clientId);
            userMsg.setEdited(0);
            messageAppender.appendWithRetry(userMsg);
            // 首条用户消息（seq==1）触发标题自动生成
            firstInteraction = userMsg.getSeq() != null && userMsg.getSeq() == 1;
            inserted++;
        }

        if (assistantContent != null && !assistantContent.isBlank()) {
            SessionMessage aiMsg = new SessionMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setSenderId("agent");
            aiMsg.setMessageType("assistant");
            aiMsg.setContent(assistantContent);
            aiMsg.setEdited(0);
            messageAppender.appendWithRetry(aiMsg);
            inserted++;
        }

        if (inserted > 0) {
            messageAppender.bumpSessionCounters(sessionId, inserted);
        }

        // 首轮问答完成后异步生成会话标题（T3.3）
        if (firstInteraction) {
            titleService.generateIfAbsent(sessionId, userContent, assistantContent);
        }

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

    private Message toLlmMessage(SessionMessage message) {
        String role = "user".equals(message.getMessageType()) ? "user" : "assistant";
        return Message.builder()
                .role(role)
                .content(message.getContent())
                .build();
    }
}
