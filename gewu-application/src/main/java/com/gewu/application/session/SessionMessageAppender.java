package com.gewu.application.session;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 会话消息追加器 - 统一的消息落库并发防护入口。
 * <p>seq 生成采用 MAX(seq)+1 配合 uk(session_id, seq) 唯一键冲突重试，
 * 会话计数器走原子 UPDATE（替代应用层读改写），clientId 提供幂等查询。
 * MessageService（协作消息）与 SessionContextService（AI 交互落库）共用，
 * 消除此前两套 seq 策略不一致导致的并发冲突。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionMessageAppender {

    private static final int MAX_SEQ_RETRY = 3;

    private final SessionMessageMapper sessionMessageMapper;
    private final SessionMapper sessionMapper;

    /**
     * 带 seq 唯一键冲突重试的消息插入，成功后原子递增会话计数。
     *
     * @return 已落库消息（含最终 seq）
     * @throws BusinessException 重试耗尽仍冲突（极端并发）
     */
    public SessionMessage appendWithRetry(SessionMessage message) {
        DuplicateKeyException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_SEQ_RETRY; attempt++) {
            Integer maxSeq = sessionMessageMapper.selectMaxSeq(message.getSessionId());
            message.setSeq((maxSeq == null ? 0 : maxSeq) + 1);
            try {
                sessionMessageMapper.insert(message);
                sessionMapper.incrementMessageCount(message.getSessionId(), 1, System.currentTimeMillis());
                return message;
            } catch (DuplicateKeyException e) {
                lastConflict = e;
                log.warn("消息 seq 唯一键冲突，第 {} 次重试: sessionId={}, seq={}",
                        attempt, message.getSessionId(), message.getSeq());
            }
        }
        throw BusinessException.of(ResultCode.PARAM_INVALID,
                "消息发送过于频繁，请稍后重试: " + (lastConflict != null ? lastConflict.getMessage() : ""));
    }

    /**
     * 按 clientId 幂等查询：命中说明该发送动作已落库，调用方应直接返回已有消息。
     *
     * @return 已存在的消息；clientId 为空或未命中返回 null
     */
    public SessionMessage findByIdempotentKey(String sessionId, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return sessionMessageMapper.selectByClientId(sessionId, clientId);
    }
}
