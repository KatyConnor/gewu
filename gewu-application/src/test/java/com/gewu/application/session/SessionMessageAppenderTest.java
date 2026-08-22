package com.gewu.application.session;

import com.gewu.common.result.BusinessException;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionMessageAppender} 单元测试（T1.4 修复验证）。
 * <p>验证 seq 唯一键冲突重试、计数器原子更新与 clientId 幂等查询。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话消息追加器")
class SessionMessageAppenderTest {

    @Mock
    private SessionMessageMapper messageMapper;

    @Mock
    private SessionMapper sessionMapper;

    private SessionMessageAppender appender;

    @BeforeEach
    void setUp() {
        appender = new SessionMessageAppender(messageMapper, sessionMapper);
    }

    private SessionMessage message() {
        SessionMessage msg = new SessionMessage();
        msg.setSessionId("sess-1");
        msg.setContent("hello");
        return msg;
    }

    @Test
    @DisplayName("首次插入成功：seq 取 MAX(seq)+1 且计数器原子递增")
    void appendSuccessFirstTry() {
        when(messageMapper.selectMaxSeq("sess-1")).thenReturn(7);
        when(messageMapper.insert(any(SessionMessage.class))).thenReturn(1);
        when(sessionMapper.incrementMessageCount(eq("sess-1"), anyInt(), anyLong())).thenReturn(1);

        SessionMessage result = appender.appendWithRetry(message());

        assertEquals(8, result.getSeq());
        verify(messageMapper, times(1)).insert(any(SessionMessage.class));
        verify(sessionMapper, times(1)).incrementMessageCount(eq("sess-1"), eq(1), anyLong());
    }

    @Test
    @DisplayName("seq 冲突时重试：重新计算 MAX(seq) 后插入成功")
    void appendRetriesOnSeqConflict() {
        when(messageMapper.selectMaxSeq("sess-1")).thenReturn(7, 9);
        when(messageMapper.insert(any(SessionMessage.class)))
                .thenThrow(new DuplicateKeyException("uk_session_message_seq"))
                .thenReturn(1);
        when(sessionMapper.incrementMessageCount(eq("sess-1"), anyInt(), anyLong())).thenReturn(1);

        SessionMessage result = appender.appendWithRetry(message());

        // 第一次 seq=8 冲突，第二次按新 MAX(seq)=9 计算 seq=10
        assertEquals(10, result.getSeq());
        verify(messageMapper, times(2)).selectMaxSeq("sess-1");
        verify(messageMapper, times(2)).insert(any(SessionMessage.class));
        // 交错顺序：查最大 seq -> 插入冲突 -> 重查 -> 插入成功
        InOrder inOrder = inOrder(messageMapper);
        inOrder.verify(messageMapper).selectMaxSeq("sess-1");
        inOrder.verify(messageMapper).insert(any(SessionMessage.class));
        inOrder.verify(messageMapper).selectMaxSeq("sess-1");
        inOrder.verify(messageMapper).insert(any(SessionMessage.class));
        // 冲突那次不递增计数，成功后仅一次
        verify(sessionMapper, times(1)).incrementMessageCount(eq("sess-1"), eq(1), anyLong());
    }

    @Test
    @DisplayName("连续冲突超过重试上限：抛业务异常且不递增计数")
    void appendThrowsAfterRetryExhausted() {
        when(messageMapper.selectMaxSeq("sess-1")).thenReturn(7);
        when(messageMapper.insert(any(SessionMessage.class)))
                .thenThrow(new DuplicateKeyException("uk_session_message_seq"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> appender.appendWithRetry(message()));
        assertTrue(ex.getMessage().contains("频繁"));

        verify(messageMapper, times(3)).insert(any(SessionMessage.class));
        verify(sessionMapper, never()).incrementMessageCount(any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("会话无历史消息时 seq 从 1 开始")
    void appendStartsFromSeqOne() {
        when(messageMapper.selectMaxSeq("sess-1")).thenReturn(null);
        when(messageMapper.insert(any(SessionMessage.class))).thenReturn(1);
        when(sessionMapper.incrementMessageCount(eq("sess-1"), anyInt(), anyLong())).thenReturn(1);

        assertEquals(1, appender.appendWithRetry(message()).getSeq());
    }

    @Test
    @DisplayName("幂等查询：clientId 为空直接返回 null 不查库")
    void idempotentLookupSkipsBlankClientId() {
        assertNull(appender.findByIdempotentKey("sess-1", null));
        assertNull(appender.findByIdempotentKey("sess-1", "  "));
        verify(messageMapper, never()).selectByClientId(any(), any());
    }

    @Test
    @DisplayName("幂等查询：命中返回已落库消息")
    void idempotentLookupReturnsExisting() {
        SessionMessage existing = message();
        existing.setId("msg-99");
        existing.setClientId("client-1");
        when(messageMapper.selectByClientId("sess-1", "client-1")).thenReturn(existing);

        assertSame(existing, appender.findByIdempotentKey("sess-1", "client-1"));
    }
}
