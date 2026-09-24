package com.gewu.application.agent;

import com.gewu.application.agent.dto.AgentExecutionDTO;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Agent 真暂停校验测试：status=0 的智能体拒绝新对话，
 * 覆盖 legacy 链路（loadAgent）与 wenshi 链路（beginExecutionRecord）两个入口。
 */
@ExtendWith(MockitoExtension.class)
class AgentExecutionEnginePauseTest {

    @Mock
    private com.gewu.agent.engine.core.AgentExecutor agentExecutor;

    @Mock
    private AgentMessageBuilder messageBuilder;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private AgentExecutionService agentExecutionService;

    private AgentExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AgentExecutionEngine(agentExecutor, messageBuilder, agentMapper,
                sessionMapper, agentExecutionService, null, null);
    }

    private Agent agentOf(Integer status) {
        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setStatus(status);
        return agent;
    }

    private AgentExecutionRequest requestOf(String agentId) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setAgentId(agentId);
        request.setMessage("你好");
        return request;
    }

    @Test
    @DisplayName("已暂停智能体（status=0）走 legacy 链路执行对话时被拒绝")
    void executeAgentRejectsPausedAgent() {
        when(agentMapper.selectById("agent-1")).thenReturn(agentOf(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.executeAgent(requestOf("agent-1")));
        assertEquals(ResultCode.AGENT_PAUSED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("已暂停智能体（status=0）wenshi 链路入口被拒绝（异常不被账本吞掉）")
    void beginExecutionRecordRejectsPausedAgent() {
        when(agentMapper.selectById("agent-1")).thenReturn(agentOf(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> engine.beginExecutionRecord(requestOf("agent-1")));
        assertEquals(ResultCode.AGENT_PAUSED.getCode(), ex.getCode());
        verify(agentExecutionService, never()).createExecution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("运行中智能体（status=1）wenshi 链路正常创建账本记录")
    void beginExecutionRecordAllowsRunningAgent() {
        when(agentMapper.selectById("agent-1")).thenReturn(agentOf(1));
        when(agentExecutionService.createExecution(eq("agent-1"), any(), any(), any()))
                .thenReturn(AgentExecutionDTO.builder().executionId("exec-1").build());

        String executionId = engine.beginExecutionRecord(requestOf("agent-1"));

        assertEquals("exec-1", executionId);
    }

    @Test
    @DisplayName("未绑定智能体的请求（agentId 为空）不做暂停校验，保持原行为")
    void beginExecutionRecordSkipsCheckWhenNoAgent() {
        String result = engine.beginExecutionRecord(requestOf(null));

        assertNull(result);
        verifyNoInteractions(agentExecutionService);
    }
}
