package com.gewu.desktop.sandbox;

import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.desktop.adapter.LocalFileService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2-1 本地 Docker 沙箱执行器验证（ADR-008 对齐清单的可自动化子集）：
 * 容器常驻复用 / shell 执行 / 工作区卷挂载（code_execute 写 → 文件工具可见）/ 降级外显。
 * 需要本机 Docker（无 Docker 时沙箱相关断言自动跳过）。
 *
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalDockerSandboxExecutorTest {

    @Autowired
    private SandboxExecutor sandboxExecutor;

    @Autowired
    private com.gewu.desktop.adapter.LocalDockerSandboxExecutor localExecutor;

    @Autowired
    private LocalFileService fileService;

    private static boolean dockerOk;

    @Test
    @Order(1)
    void dockerAvailabilityProbe() {
        dockerOk = localExecutor.isDockerAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerOk, "本机无 Docker，跳过沙箱验证");
    }

    @Test
    @Order(2)
    void shellExecutionInContainer() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerOk);
        ExecResult r = sandboxExecutor.executeCode("shell", "echo hello-from-sandbox", 60);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getStdout()).contains("hello-from-sandbox");
        assertThat(r.getExitCode()).isZero();
    }

    @Test
    @Order(3)
    void workspaceVolumeSharedWithFileTools() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerOk);
        // 文件工具写 → 沙箱内可见（同一持久视图）
        fileService.writeFile("sbx-test-session", "shared.txt", "卷挂载验证内容", false);
        ExecResult r = ((com.gewu.desktop.adapter.LocalDockerSandboxExecutor) sandboxExecutor)
                .executeForSession("sbx-test-session", "shell",
                "cat /workspace/shared.txt && echo 'from-sandbox' >> /workspace/shared.txt", 60);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getStdout()).contains("卷挂载验证内容");
        // 沙箱内追加 → 文件工具可见（反向）
        String back = fileService.readFile("sbx-test-session", "shared.txt");
        assertThat(back).contains("from-sandbox");
        Files.deleteIfExists(Path.of("workspace/shared.txt"));
    }

    @Test
    @Order(4)
    void unknownLanguageHonestDegradation() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerOk);
        ExecResult r = sandboxExecutor.executeCode("cobol", "DISPLAY 'X'", 10);
        // 镜像无 cobol → 失败但信息明确（不假装成功）
        assertThat(r.isSuccess()).isFalse();
    }

    private com.gewu.agent.engine.tool.ToolContext newToolContext(String sessionId) {
        com.gewu.agent.engine.tool.ToolContext ctx = new com.gewu.agent.engine.tool.ToolContext();
        ctx.setSessionId(sessionId);
        ctx.setUserId("test");
        return ctx;
    }
}
