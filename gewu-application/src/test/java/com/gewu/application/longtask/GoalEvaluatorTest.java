package com.gewu.application.longtask;

import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.domain.longtask.AgentLongTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/** 目标达标双层评估器单测（docs/design/45 §6）：机械检查命令/产物 + 越界拒绝 + 无标准不达标 */
class GoalEvaluatorTest {

    private SandboxClient sandboxClient;
    private LlmClientRegistry registry;
    private GoalEvaluator evaluator;

    private AgentLongTask task;

    @BeforeEach
    void setUp() {
        sandboxClient = Mockito.mock(SandboxClient.class);
        registry = Mockito.mock(LlmClientRegistry.class);
        evaluator = new GoalEvaluator(sandboxClient, registry);

        task = new AgentLongTask();
        task.setId("01TASK");
        task.setGoalText("完成量化交易应用服务");
    }

    private AgentLongTask taskWithCriteria(String criteriaJson) {
        task.setCriteriaJson(criteriaJson);
        return task;
    }

    @Test
    @DisplayName("command 检查：exit 0 且输出匹配 expect → 通过")
    void commandCheckPass() {
        var resp = new ExecCommandResponse();
        resp.setExitCode(0);
        resp.setStdout("BUILD SUCCESS");
        when(sandboxClient.execCommand(anyString(), contains("mvn"), anyInt())).thenReturn(resp);

        var result = evaluator.evaluate(taskWithCriteria(
                        "{\"statement\":\"构建通过\",\"checks\":[{\"type\":\"command\",\"name\":\"构建\",\"cmd\":\"mvn -q test\",\"expect\":\"BUILD SUCCESS\"}]}"),
                "sb-1", "");

        assertTrue(result.achieved());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).pass());
    }

    @Test
    @DisplayName("command 检查：exit 非 0 → 不通过")
    void commandCheckFail() {
        var resp = new ExecCommandResponse();
        resp.setExitCode(1);
        resp.setStderr("compilation error");
        when(sandboxClient.execCommand(anyString(), anyString(), anyInt())).thenReturn(resp);

        var result = evaluator.evaluate(taskWithCriteria(
                "{\"statement\":\"构建通过\",\"checks\":[{\"type\":\"command\",\"cmd\":\"mvn test\"}]}"), "sb-1", "");

        assertFalse(result.achieved());
        assertFalse(result.items().get(0).pass());
    }

    @Test
    @DisplayName("file_exists 检查：产物存在通过；不存在不通过")
    void fileExistsCheck() {
        var exists = new ExecCommandResponse();
        exists.setExitCode(0);
        exists.setStdout("EXISTS");
        var missing = new ExecCommandResponse();
        missing.setExitCode(1);

        when(sandboxClient.execCommand(anyString(), contains("backtest.md"), anyInt())).thenReturn(exists);
        when(sandboxClient.execCommand(anyString(), contains("missing.md"), anyInt())).thenReturn(missing);

        var ok = evaluator.evaluate(taskWithCriteria(
                "{\"statement\":\"有回测报告\",\"checks\":[{\"type\":\"file_exists\",\"path\":\"projects/eleven/report/backtest.md\"}]}"), "sb-1", "");
        var no = evaluator.evaluate(taskWithCriteria(
                "{\"statement\":\"有回测报告\",\"checks\":[{\"type\":\"file_exists\",\"path\":\"projects/eleven/report/missing.md\"}]}"), "sb-1", "");

        assertTrue(ok.achieved());
        assertFalse(no.achieved());
    }

    @Test
    @DisplayName("无 checks → achieved=false（不允许无标准即达标）")
    void emptyChecksNotAchieved() {
        var result = evaluator.evaluate(taskWithCriteria("{\"statement\":\"只有目标声明\"}"), "sb-1", "");
        assertFalse(result.achieved());
        assertTrue(result.items().isEmpty());
    }

    @Test
    @DisplayName("路径含 .. 越界 → 该检查不通过（L3-SEC-006）")
    void pathTraversalRejected() {
        var resp = new ExecCommandResponse();
        resp.setExitCode(0);
        resp.setStdout("EXISTS");
        when(sandboxClient.execCommand(anyString(), anyString(), anyInt())).thenReturn(resp);

        var result = evaluator.evaluate(taskWithCriteria(
                "{\"statement\":\"x\",\"checks\":[{\"type\":\"file_exists\",\"path\":\"../../etc/passwd\"}]}"), "sb-1", "");

        assertFalse(result.achieved());
        assertFalse(result.items().get(0).pass());
    }
}
