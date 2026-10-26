package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
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
    /** 跨轮回放：文件清单注入开关与条数上限（设计文档 42 项一，工具调用不落库的补偿） */
    @Value("${gewu.session.file-inventory-enabled:true}")
    boolean fileInventoryEnabled;
    @Value("${gewu.session.file-inventory-limit:20}")
    int fileInventoryLimit;
    /** 跨轮回放：近期工具要点注入开关与条数上限（素材为 assistant metadata.process） */
    @Value("${gewu.session.tool-brief-enabled:true}")
    boolean toolBriefEnabled;
    @Value("${gewu.session.tool-brief-limit:10}")
    int toolBriefLimit;

    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    private final SessionMessageMapper sessionMessageMapper;
    private final SessionMapper sessionMapper;
    private final CacheService cacheService;
    private final ContextCompressor contextCompressor;
    private final SessionMessageAppender messageAppender;
    private final SessionTitleService titleService;
    private final SessionFileWorkspaceService sessionFileWorkspaceService;

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
            List<Message> result = new ArrayList<>(messages.stream()
                    .map(this::toLlmMessage)
                    .toList());
            appendWorkspaceBriefs(sessionId, result);
            return result;
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

        // 4. 工作空间状态与近期工具要点（跨轮回放，压缩后仍存活——system 消息不参与压缩）
        appendWorkspaceBriefs(sessionId, result);

        // 缓存压缩结果
        if (!compressed.isEmpty()) {
            String cacheKey = CacheKeys.messages(sessionId) + ":compressed";
            cacheService.set(cacheKey, compressed, CACHE_TTL);
        }

        return result;
    }

    /**
     * 跨轮回放注入（设计文档 42 项一）：工具调用不落库回放，模型每轮只能重新猜
     * 路径/重复探查。此处以两条 system 消息补偿——文件清单（session_file_change
     * 台账）+ 近期工具要点（assistant metadata.process 时间线条目）。
     * 注入失败静默降级，不影响对话主链路。
     */
    private void appendWorkspaceBriefs(String sessionId, List<Message> result) {
        if (fileInventoryEnabled) {
            try {
                List<String> lines = sessionFileWorkspaceService.fileInventorySummary(sessionId, fileInventoryLimit);
                if (!lines.isEmpty()) {
                    result.add(Message.builder().role("system")
                            .content("[工作空间状态] 本会话已写入/修改的文件（路径相对工作空间根，"
                                    + "勿带 workspace/ 前缀，可直接 read_file/edit_file 访问）：\n"
                                    + String.join("\n", lines))
                            .build());
                }
            } catch (Exception e) {
                log.debug("文件清单注入失败: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }
        if (toolBriefEnabled) {
            try {
                String brief = buildToolBrief(sessionId, toolBriefLimit);
                if (!brief.isEmpty()) {
                    result.add(Message.builder().role("system")
                            .content("[近期工具要点] 本会话近期工具调用与结果摘要：\n" + brief)
                            .build());
                }
            } catch (Exception e) {
                log.debug("工具要点注入失败: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }
    }

    /** 从最近消息的 assistant metadata.process 提取工具条目（k=tool：n 名称 / a 参数 / r 结果） */
    private String buildToolBrief(String sessionId, int limit) {
        List<SessionMessage> messages = sessionMessageMapper.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .orderByDesc(SessionMessage::getSeq)
                        .last("LIMIT 40"));
        List<String> newestFirst = new ArrayList<>();
        for (SessionMessage m : messages) {
            if (!"assistant".equals(m.getMessageType()) || m.getMetadata() == null || m.getMetadata().isBlank()) {
                continue;
            }
            try {
                JsonNode proc = METADATA_MAPPER.readTree(m.getMetadata()).path("process");
                if (!proc.isArray()) {
                    continue;
                }
                for (JsonNode entry : proc) {
                    if (!"tool".equals(entry.path("k").asText())) {
                        continue;
                    }
                    String args = entry.path("a").asText("");
                    if (args.length() > 120) {
                        args = args.substring(0, 120) + "…";
                    }
                    String res = entry.path("r").asText("");
                    if (res.length() > 200) {
                        res = res.substring(0, 200) + "…";
                    }
                    newestFirst.add("- " + entry.path("n").asText("") + "(" + args + ")"
                            + (res.isBlank() ? "" : " → " + res));
                }
            } catch (Exception e) {
                // 单条 metadata 损坏只跳过该条
                log.debug("解析消息 metadata 失败: messageId={}, err={}", m.getId(), e.getMessage());
            }
        }
        if (newestFirst.isEmpty()) {
            return "";
        }
        List<String> selected = newestFirst.subList(0, Math.min(limit, newestFirst.size()));
        Collections.reverse(selected);
        return String.join("\n", selected);
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
     * <p>计数说明：message_count 由 {@link SessionMessageAppender#appendWithRetry} 逐行原子 +1，
     * 此处不再叠加轮次级 bump（此前每轮多计一倍，侧栏消息数虚高）。
     */
    public void appendChatInteraction(String sessionId, String userId, String userContent,
                                      String assistantContent, String clientId, String assistantMetadata) {
        appendUserMessage(sessionId, userId, userContent, clientId);
        if (assistantContent != null && !assistantContent.isBlank()) {
            appendAssistantMessage(sessionId, assistantContent, assistantMetadata, clientId);
        }
    }

    /**
     * 用户消息即时落库（断连不中断修复）：流式请求进入时即保存用户输入，
     * 此后无论断连/异常/进程重启，用户消息都不再丢失。
     * <p>幂等键 = clientId（uk_session_message_client），重放/网络重试安全。
     */
    public void appendUserMessage(String sessionId, String userId, String userContent, String clientId) {
        if (sessionId == null || sessionId.isBlank() || userContent == null || userContent.isBlank()) {
            return;
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.warn("会话不存在，跳过用户消息持久化: sessionId={}", sessionId);
            return;
        }
        if (messageAppender.findByIdempotentKey(sessionId, clientId) != null) {
            log.info("用户消息已落库，幂等跳过: sessionId={}, clientId={}", sessionId, clientId);
            return;
        }
        SessionMessage userMsg = new SessionMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setSenderId(userId);
        userMsg.setMessageType("user");
        userMsg.setContent(userContent);
        userMsg.setClientId(clientId);
        userMsg.setEdited(0);
        messageAppender.appendWithRetry(userMsg);
        // 首条用户消息（seq==1）触发标题自动生成；此时助手回复尚未产生，
        // 仅以用户输入生成（generateIfAbsent 内部容忍空助手内容）
        if (userMsg.getSeq() != null && userMsg.getSeq() == 1) {
            titleService.generateIfAbsent(sessionId, userContent, null);
        }
        cacheService.delete(CacheKeys.messages(sessionId));
    }

    /**
     * 助手消息落库（断连不中断修复）：由运行链路的 doFinally 终结回调调用，
     * 与请求线程生命周期解耦——客户端断连后任务跑完仍会执行到这里。
     * <p>幂等键 = clientId + ":ai"（与用户消息键区分，避免整轮被 user 行误判已落库）。
     */
    public void appendAssistantMessage(String sessionId, String assistantContent,
                                       String assistantMetadata, String clientId) {
        if (sessionId == null || sessionId.isBlank() || assistantContent == null || assistantContent.isBlank()) {
            return;
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.warn("会话不存在，跳过助手消息持久化: sessionId={}", sessionId);
            return;
        }
        String assistantClientId = (clientId == null || clientId.isBlank()) ? null : clientId + ":ai";
        if (messageAppender.findByIdempotentKey(sessionId, assistantClientId) != null) {
            log.info("AI 回复已落库，幂等跳过: sessionId={}, clientId={}", sessionId, assistantClientId);
            return;
        }
        SessionMessage aiMsg = new SessionMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setSenderId("agent");
        aiMsg.setMessageType("assistant");
        aiMsg.setContent(assistantContent);
        aiMsg.setMetadata(assistantMetadata);
        aiMsg.setClientId(assistantClientId);
        aiMsg.setEdited(0);
        messageAppender.appendWithRetry(aiMsg);
        cacheService.delete(CacheKeys.messages(sessionId));
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
