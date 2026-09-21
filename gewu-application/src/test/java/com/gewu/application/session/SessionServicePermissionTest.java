package com.gewu.application.session;

import com.gewu.application.session.dto.CreateSessionCommand;
import com.gewu.application.session.dto.SessionDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMember;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMemberMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SessionService 权限隔离测试 (CR-015).
 */
@ExtendWith(MockitoExtension.class)
class SessionServicePermissionTest {

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private SessionMemberMapper sessionMemberMapper;

    @Mock
    private SessionMessageMapper sessionMessageMapper;

    @Mock
    private com.gewu.infrastructure.mapper.SessionFileChangeMapper sessionFileChangeMapper;

    @Mock
    private com.gewu.infrastructure.mapper.SessionFileChangeEventMapper sessionFileChangeEventMapper;

    @Mock
    private com.gewu.infrastructure.mapper.AgentExecutionMapper agentExecutionMapper;

    @Mock
    private WorkspaceMapper workspaceMapper;

    private SessionService sessionService;

    @BeforeEach
    void setUp() throws Exception {
        sessionService = new SessionService(sessionMapper, sessionMemberMapper, sessionMessageMapper,
                sessionFileChangeMapper, sessionFileChangeEventMapper, agentExecutionMapper, workspaceMapper);
    }

    @Test
    @DisplayName("CR-015: 用户 A 无法访问用户 B 的私有会话")
    void getSession_userACannotAccessUserBPrivateSession() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        // 模拟会话属于用户 B，且为私有
        Session session = new Session();
        session.setId("session1");
        session.setTitle("User B's Session");
        session.setIsPublic(0); // 私有
        when(sessionMapper.selectById("session1")).thenReturn(session);

        // 用户 A 不是会话成员
        when(sessionMemberMapper.selectCount(any())).thenReturn(0L);

        // 应该抛出 FORBIDDEN 异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            sessionService.getSession("session1");
        });
        assertEquals(10004, exception.getCode()); // FORBIDDEN

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-015: 用户可以访问自己参与的会话")
    void getSession_userCanAccessOwnSession() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        // 模拟会话属于用户 A
        Session session = new Session();
        session.setId("session1");
        session.setTitle("User A's Session");
        session.setIsPublic(0);
        when(sessionMapper.selectById("session1")).thenReturn(session);

        // 用户 A 是会话成员
        when(sessionMemberMapper.selectCount(any())).thenReturn(1L);

        // 应该正常返回
        SessionDTO result = sessionService.getSession("session1");
        assertNotNull(result);
        assertEquals("session1", result.getSessionId());

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-015: 任何用户可以访问公开会话")
    void getSession_anyUserCanAccessPublicSession() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        // 模拟公开会话
        Session session = new Session();
        session.setId("session1");
        session.setTitle("Public Session");
        session.setIsPublic(1); // 公开
        when(sessionMapper.selectById("session1")).thenReturn(session);

        // 应该正常返回，不检查成员资格
        SessionDTO result = sessionService.getSession("session1");
        assertNotNull(result);
        assertEquals("session1", result.getSessionId());

        // 验证没有调用成员资格检查
        verify(sessionMemberMapper, never()).selectCount(any());

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-015: 未认证用户无法访问会话")
    void getSession_unauthorizedUserCannotAccess() {
        // 清除用户上下文
        UserContext.clear();

        // 应该抛出 UNAUTHORIZED 异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            sessionService.getSession("session1");
        });
        assertEquals(10003, exception.getCode()); // UNAUTHORIZED
    }

    @Test
    @DisplayName("CR-015: 用户 A 无法查看用户 B 私有会话的成员")
    void getSessionMembers_userACannotAccessUserBPrivateSessionMembers() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        // 模拟会话属于用户 B，且为私有
        Session session = new Session();
        session.setId("session1");
        session.setIsPublic(0);
        when(sessionMapper.selectById("session1")).thenReturn(session);

        // 用户 A 不是会话成员
        when(sessionMemberMapper.selectCount(any())).thenReturn(0L);

        // 应该抛出 FORBIDDEN 异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            sessionService.getSessionMembers("session1");
        });
        assertEquals(10004, exception.getCode()); // FORBIDDEN

        UserContext.clear();
    }
}
