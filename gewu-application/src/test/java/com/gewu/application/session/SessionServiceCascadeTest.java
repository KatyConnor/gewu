package com.gewu.application.session;

import com.gewu.common.context.UserContext;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import com.gewu.infrastructure.mapper.SessionFileChangeEventMapper;
import com.gewu.infrastructure.mapper.SessionFileChangeMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMemberMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话删除级联清理回归测试（P1 存储闭环）：
 * 删除会话必须级联清理消息、成员、文件变更、写入事件与执行账本。
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceCascadeTest {

    @Mock SessionMapper sessionMapper;
    @Mock SessionMemberMapper sessionMemberMapper;
    @Mock SessionMessageMapper sessionMessageMapper;
    @Mock SessionFileChangeMapper sessionFileChangeMapper;
    @Mock SessionFileChangeEventMapper sessionFileChangeEventMapper;
    @Mock AgentExecutionMapper agentExecutionMapper;
    @Mock WorkspaceMapper workspaceMapper;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionMapper, sessionMemberMapper, sessionMessageMapper,
                sessionFileChangeMapper, sessionFileChangeEventMapper, agentExecutionMapper, workspaceMapper);
        UserContext.set(UserContext.builder().userId("u-1").username("u-1").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("删除会话级联清理五类从属数据后删除会话本身")
    void deleteSessionCascades() {
        Session session = new Session();
        session.setId("s-1");
        when(sessionMapper.selectById("s-1")).thenReturn(session);
        when(sessionMemberMapper.selectCount(any())).thenReturn(1L);

        sessionService.deleteSession("s-1");

        verify(sessionMessageMapper).delete(any());
        verify(sessionMemberMapper).delete(any());
        verify(sessionFileChangeMapper).delete(any());
        verify(sessionFileChangeEventMapper).delete(any());
        verify(agentExecutionMapper).delete(any());
        verify(sessionMapper).deleteById("s-1");
    }
}
