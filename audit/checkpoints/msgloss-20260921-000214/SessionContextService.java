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
import org.springframework.beans.factory.annotation.Value;
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
    private static final Duration CACHE_TTL = Duration.ofHours(2);
    /** 历史压缩阈值（tokens，用户实报：原硬编码 4000 过低，长程任务几轮即触发压缩丢失任务连续性） */
    @Value("${gewu.session.context-compact-threshold-tokens:32000}")
    int contextCompactThresholdTokens;
    /** 压缩时保留的最近消息条数（原硬编码 6 ≈ 3 轮对话，提到 24 ≈ 最近 12 轮） */
    @Value("${gewu.session.context-compact-keep-recent:24}")
    int contextCompactKeepRecent;

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

        if (estimatedTokens <= contextCompactThresholdTokens) {
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
        int splitIdx = Math.max(0, regularMessages.size() - contextCompactKeepRecent);
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
        // 任务计划快照跨压缩存活（用户实报：压缩后模型丢失任务计划状态，
        // 误判"任务基本完成"而提前总结收场）：提取被压缩区间最后一个 PLAN 标记段
        String planSnapshot = extractLastPlanMarker(oldMessages);

        // 构建结果：system 消息 + 压缩摘要 + 最近消息
        List<Message> result = new ArrayList<>();
        
        // 1. 保留所有 system 消息
        for (SessionMessage sm : systemMessages) {
            result.add(Message.builder()
                    .role("system")
                    .content(sm.getContent())
                    .build());
        }
        
        // 2. 添加压缩摘要（如果有）——文案明示"任务可能未完成"，对冲模型读到摘要即收尾的倾向
        if (!compressed.isEmpty()) {
            result.add(Message.builder()
                    .role("system")
                    .content("[对话历史摘要] 以下是本次会话早期对话的摘要，任务可能尚未完成——"
                            + "请结合任务计划快照（如有）与最近消息继续执行剩余步骤，"
                            + "勿仅凭本摘要判断任务已完成。\n" + compressed)
                    .build());
        }

        // 2.5 任务计划快照：让模型在压缩后仍能看到完整任务清单与步骤状态
        if (!planSnapshot.isEmpty()) {
            result.add(Message.builder()
                    .role("system")
                    .content("[任务计划快照] 当前任务的最新计划状态如下，请据此继续推进未完成的步骤：\n" + planSnapshot)
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

        if (estimatedTokens <= contextCompactThresholdTokens) {
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
        appendChatInteraction(sessionId, userId, userContent, assistantContent, clientId, null);
    }

    /**
     * 带助手消息元数据的交互落库（S9）：metadata 携带过程时间线摘要 JSON，
     * 供前端历史消息还原折叠过程视图。
     */
    public void appendChatInteraction(String sessionId, String userId, String userContent,
                                      String assistantContent, String clientId, String assistantMetadata) {
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
            aiMsg.setMetadata(assistantMetadata);
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

    /**
     * 提取消息区间中最后一个 &lt;!--PLAN:{...}--&gt; 标记段（任务计划跨压缩存活）。
     * 无计划标记时返回空串。
     */
    private String extractLastPlanMarker(List<SessionMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).getContent();
            if (content == null) {
                continue;
            }
            int idx = content.lastIndexOf("\n<!--PLAN:");
            if (idx < 0) {
                continue;
            }
            int end = content.indexOf("-->", idx);
            if (end < 0) {
                continue;
            }
            return content.substring(idx + 1, end + 3); // 含 <!--PLAN: ... --> 完整标记
        }
        return "";
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
