package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.AgentEngineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultCodeScanner} 危险代码扫描测试。
 */
@DisplayName("沙箱代码扫描器")
class DefaultCodeScannerTest {

    private final DefaultCodeScanner scanner = new DefaultCodeScanner();

    @Test
    @DisplayName("Python 危险系统调用被拦截（os.system/subprocess）")
    void pythonDangerousCallsBlocked() {
        assertThatThrownBy(() -> scanner.scan("os.system('rm /tmp/x')", "python"))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("危险操作");
        assertThatThrownBy(() -> scanner.scan("subprocess.run(['ls'])", "python"))
                .isInstanceOf(AgentEngineException.class);
        assertThatThrownBy(() -> scanner.scan("result = eval(user_input)", "python"))
                .isInstanceOf(AgentEngineException.class);
        assertThatThrownBy(() -> scanner.scan("shutil.rmtree('/data')", "python"))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("Python 网络与权限提升操作被拦截")
    void pythonNetworkAndPrivilegeBlocked() {
        assertThatThrownBy(() -> scanner.scan("requests.get('http://evil.com')", "python"))
                .isInstanceOf(AgentEngineException.class);
        assertThatThrownBy(() -> scanner.scan("os.setuid(0)", "python"))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("Shell 危险命令被拦截（rm -rf/shutdown 等）")
    void shellDangerousCommandsBlocked() {
        assertThatThrownBy(() -> scanner.scan("rm -rf /", "shell"))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("危险命令");
        assertThatThrownBy(() -> scanner.scan("shutdown now", "bash"))
                .isInstanceOf(AgentEngineException.class);
        assertThatThrownBy(() -> scanner.scan("chmod 777 /etc/passwd", "sh"))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("敏感路径访问被拦截（任意语言）")
    void sensitivePathBlocked() {
        assertThatThrownBy(() -> scanner.scan("open('/etc/passwd')", "python"))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("敏感路径");
        assertThatThrownBy(() -> scanner.scan("cat /etc/shadow", "shell"))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("敏感环境变量读取被拦截")
    void sensitiveEnvVarsBlocked() {
        assertThatThrownBy(() -> scanner.scan("print(os.environ['PASSWORD'])", "python"))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("敏感环境变量");
        assertThatThrownBy(() -> scanner.scan("echo $API_KEY && os.environ", "shell"))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("正常代码与空值通过")
    void safeCodePasses() {
        assertThatCode(() -> scanner.scan("print('hello world')", "python"))
                .doesNotThrowAnyException();
        assertThatCode(() -> scanner.scan("ls -la", "shell"))
                .doesNotThrowAnyException();
        assertThatCode(() -> scanner.scan(null, "python")).doesNotThrowAnyException();
        assertThatCode(() -> scanner.scan("  ", "python")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("语言缺省按 Python 处理")
    void nullLanguageDefaultsToPython() {
        assertThatThrownBy(() -> scanner.scan("os.system('x')", null))
                .isInstanceOf(AgentEngineException.class);
    }
}
