package com.gewu.application.agent;

import com.gewu.application.agent.dto.AgentDTO;
import com.gewu.application.agent.dto.CreateAgentCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.domain.agent.Agent;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentSkillMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentService 权限隔离测试 (CR-014).
 */
@ExtendWith(MockitoExtension.class)
class AgentServicePermissionTest {

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AgentToolMapper agentToolMapper;

    @Mock
    private AgentExecutionMapper agentExecutionMapper;

    @Mock
    private AgentSkillMapper agentSkillMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private SessionMapper sessionMapper;

    private AgentService agentService;

    @BeforeEach
    void setUp() throws Exception {
        agentService = new AgentService(agentMapper, agentToolMapper, agentExecutionMapper, agentSkillMapper, skillMapper, sessionMapper);
    }

    @Test
    @DisplayName("CR-014: 创建 Agent 时自动绑定创建者")
    void createAgent_setsCreatedBy() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        CreateAgentCommand command = new CreateAgentCommand();
        command.setAgentName("Test Agent");
        command.setModelProvider("openai");
        command.setModelName("gpt-4");

        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            Agent agent = invocation.getArgument(0);
            assertEquals("userA", agent.getCreatedBy());
            return 1;
        });

        AgentDTO result = agentService.createAgent(command);
        
        verify(agentMapper).insert(argThat(agent -> "userA".equals(agent.getCreatedBy())));

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-014: 用户 A 无法访问用户 B 的 Agent")
    void getAgent_userACannotAccessUserBAgent() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .build());

        // 模拟 Agent 属于用户 B
        Agent agent = new Agent();
        agent.setId("agent1");
        agent.setAgentName("User B's Agent");
        agent.setCreatedBy("userB");
        when(agentMapper.selectById("agent1")).thenReturn(agent);

        // 用户 A 不是管理员
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .roleCodes(List.of("USER"))
                .build());

        // 应该抛出 FORBIDDEN 异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            agentService.getAgent("agent1");
        });
        assertEquals(10004, exception.getCode()); // FORBIDDEN

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-014: 管理员可以访问任何用户的 Agent")
    void getAgent_adminCanAccessAnyAgent() {
        // 设置管理员用户
        UserContext.set(UserContext.builder()
                .userId("admin")
                .username("admin")
                .roleCodes(List.of("ADMIN"))
                .build());

        // 模拟 Agent 属于用户 B
        Agent agent = new Agent();
        agent.setId("agent1");
        agent.setAgentName("User B's Agent");
        agent.setCreatedBy("userB");
        when(agentMapper.selectById("agent1")).thenReturn(agent);
        when(sessionMapper.countByAgentIds(any())).thenReturn(List.of());

        // 应该正常返回
        AgentDTO result = agentService.getAgent("agent1");
        assertNotNull(result);
        assertEquals("agent1", result.getAgentId());

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-014: 用户可以访问自己创建的 Agent")
    void getAgent_userCanAccessOwnAgent() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .roleCodes(List.of("USER"))
                .build());

        // 模拟 Agent 属于用户 A
        Agent agent = new Agent();
        agent.setId("agent1");
        agent.setAgentName("User A's Agent");
        agent.setCreatedBy("userA");
        when(agentMapper.selectById("agent1")).thenReturn(agent);
        when(sessionMapper.countByAgentIds(any())).thenReturn(List.of());

        // 应该正常返回
        AgentDTO result = agentService.getAgent("agent1");
        assertNotNull(result);
        assertEquals("agent1", result.getAgentId());

        UserContext.clear();
    }

    @Test
    @DisplayName("CR-014: 用户 A 无法访问用户 B 的 Agent 工具")
    void getAgentTools_userACannotAccessUserBAgentTools() {
        // 设置用户 A
        UserContext.set(UserContext.builder()
                .userId("userA")
                .username("userA")
                .roleCodes(List.of("USER"))
                .build());

        // 模拟 Agent 属于用户 B
        Agent agent = new Agent();
        agent.setId("agent1");
        agent.setCreatedBy("userB");
        when(agentMapper.selectById("agent1")).thenReturn(agent);

        // 应该抛出 FORBIDDEN 异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            agentService.getAgentTools("agent1");
        });
        assertEquals(10004, exception.getCode()); // FORBIDDEN

        UserContext.clear();
    }
}
