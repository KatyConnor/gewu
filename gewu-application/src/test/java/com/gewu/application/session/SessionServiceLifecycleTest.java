package com.gewu.application.session;

import com.gewu.common.context.UserContext;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMember;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMemberMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionService} 增值生命周期测试（T3.3）：归档/分享/置顶。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话增值生命周期")
class SessionServiceLifecycleTest {

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private SessionMemberMapper sessionMemberMapper;

    @Mock
    private SessionMessageMapper sessionMessageMapper;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionMapper, sessionMemberMapper, sessionMessageMapper);
        UserContext.set(UserContext.builder().userId("user-1").build());
        // 当前用户是会话成员（部分用例不涉及，故 lenient）
        lenient().when(sessionMemberMapper.selectCount(any())).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Session session() {
        Session session = new Session();
        session.setId("sess-1");
        session.setTitle("新对话");
        session.setType(1);
        session.setStatus(0);
        session.setIsPublic(0);
        session.setPinned(0);
        session.setMessageCount(2);
        return session;
    }

    @Test
    @DisplayName("归档：状态置 2 并记录归档时间")
    void archiveSetsStatusAndTime() {
        Session session = session();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);

        var dto = sessionService.archiveSession("sess-1");

        assertEquals(2, dto.getStatus());
        assertNotNull(session.getTimeArchived());
        verify(sessionMapper).updateById(session);
    }

    @Test
    @DisplayName("取消归档：仅已归档会话可取消")
    void unarchiveOnlyForArchived() {
        Session archived = session();
        archived.setStatus(2);
        when(sessionMapper.selectById("sess-1")).thenReturn(archived);

        assertEquals(0, sessionService.unarchiveSession("sess-1").getStatus());
        assertNull(archived.getTimeArchived());

        // 进行中的会话取消归档报错
        Session active = session();
        when(sessionMapper.selectById("sess-2")).thenReturn(active);
        assertThrows(Exception.class, () -> sessionService.unarchiveSession("sess-2"));
    }

    @Test
    @DisplayName("分享：生成 slug 并公开；幂等复用既有 slug")
    void shareGeneratesSlugAndIsIdempotent() {
        Session session = session();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);

        var first = sessionService.shareSession("sess-1");
        assertNotNull(first.getSlug());
        assertEquals(10, first.getSlug().length());
        assertEquals(1, session.getIsPublic());
        assertNotNull(session.getShareUrl());

        // 二次分享复用既有 slug（幂等）
        String slugBefore = session.getSlug();
        var second = sessionService.shareSession("sess-1");
        assertEquals(slugBefore, second.getSlug());
    }

    @Test
    @DisplayName("取消分享：关闭公开并清空 slug")
    void unshareClears() {
        Session shared = session();
        shared.setIsPublic(1);
        shared.setSlug("abc123def0");
        shared.setShareUrl("/share/abc123def0");
        when(sessionMapper.selectById("sess-1")).thenReturn(shared);

        var dto = sessionService.unshareSession("sess-1");

        assertEquals(0, dto.getIsPublic());
        assertNull(sessionMapper.selectById("sess-1").getSlug());
    }

    @Test
    @DisplayName("按 slug 公开读取：脱敏创建者且仅公开可读")
    void sharedSessionRead() {
        Session shared = session();
        shared.setIsPublic(1);
        shared.setSlug("abc123def0");
        shared.setCreatedBy("owner-9");
        when(sessionMapper.selectOne(any())).thenReturn(shared);

        var dto = sessionService.getSharedSession("abc123def0");
        assertEquals("sess-1", dto.getSessionId());
        assertNull(dto.getCreatedBy(), "分享视图不应暴露创建者 ID");

        // 未公开的 slug 不可读
        when(sessionMapper.selectOne(any())).thenReturn(null);
        assertThrows(Exception.class, () -> sessionService.getSharedSession("not-shared"));
        assertThrows(Exception.class, () -> sessionService.getSharedSession(null));
    }

    @Test
    @DisplayName("置顶/取消置顶")
    void pinAndUnpin() {
        Session session = session();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);

        assertEquals(1, sessionService.pinSession("sess-1", true).getPinned());
        assertEquals(0, sessionService.pinSession("sess-1", false).getPinned());
        verify(sessionMapper, org.mockito.Mockito.times(2)).updateById(session);
    }

    @Test
    @DisplayName("非成员操作被拒绝")
    void nonMemberRejected() {
        when(sessionMemberMapper.selectCount(any())).thenReturn(0L);
        Session session = session();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);

        assertThrows(Exception.class, () -> sessionService.archiveSession("sess-1"));
        assertThrows(Exception.class, () -> sessionService.shareSession("sess-1"));
        assertThrows(Exception.class, () -> sessionService.pinSession("sess-1", true));
    }
}
