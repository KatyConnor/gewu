package com.gewu.application.workspace;

import com.gewu.common.result.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DevWorkspaceService 路径净化与 shell 转义回归测试（P0-1）：
 * 用户输入路径/项目名/仓库地址/分支/主机名在拼入 shell 前必须经过白名单校验或转义。
 */
class DevWorkspaceServicePathSanitizeTest {

    @Test
    @DisplayName("正常相对路径保留")
    void keepNormalPath() {
        assertEquals("projects/demo/src/Main.java",
                DevWorkspaceService.sanitizeWorkspaceRel("projects/demo/src/Main.java"));
    }

    @Test
    @DisplayName("中文与空格路径保留")
    void keepChineseAndSpace() {
        assertEquals("docs/中文 说明.md", DevWorkspaceService.sanitizeWorkspaceRel("docs/中文 说明.md"));
    }

    @Test
    @DisplayName("首尾斜杠剥除")
    void stripSlashes() {
        assertEquals("a/b", DevWorkspaceService.sanitizeWorkspaceRel("/a/b/"));
    }

    @Test
    @DisplayName(".. 越界路径拒绝")
    void rejectDotDot() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.sanitizeWorkspaceRel("../etc/passwd"));
    }

    @Test
    @DisplayName("shell 元字符路径拒绝")
    void rejectShellMeta() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.sanitizeWorkspaceRel("a;rm -rf /workspace"));
    }

    @Test
    @DisplayName("反引号注入拒绝")
    void rejectBacktick() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.sanitizeWorkspaceRel("a`id`b"));
    }

    @Test
    @DisplayName("空路径拒绝")
    void rejectBlank() {
        assertThrows(BusinessException.class, () -> DevWorkspaceService.sanitizeWorkspaceRel("  "));
    }

    @Test
    @DisplayName("单引号转义为 '\\'' 序列")
    void escapeSingleQuote() {
        assertEquals("'a'\\''b'", DevWorkspaceService.shellQuote("a'b"));
    }

    @Test
    @DisplayName("普通内容包裹后语义不变")
    void quotePlainContent() {
        assertEquals("'projects/demo'", DevWorkspaceService.shellQuote("projects/demo"));
    }

    @Test
    @DisplayName("项目名含空格拒绝")
    void rejectBadProjectName() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.validateProjectName("a b"));
    }

    @Test
    @DisplayName("项目名路径穿越拒绝")
    void rejectDotDotProjectName() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.validateProjectName("../evil"));
    }

    @Test
    @DisplayName("非 git 协议仓库地址拒绝")
    void rejectNonGitUrl() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.validateRepoUrl("file:///etc/passwd"));
    }

    @Test
    @DisplayName("https 仓库地址通过")
    void acceptHttpsUrl() {
        assertEquals("https://github.com/a/b.git",
                DevWorkspaceService.validateRepoUrl("https://github.com/a/b.git"));
    }

    @Test
    @DisplayName("分支名含分号拒绝")
    void rejectBadBranch() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.validateBranch("main;reboot"));
    }

    @Test
    @DisplayName("git 主机名含注入拒绝")
    void rejectBadGitHost() {
        assertThrows(BusinessException.class,
                () -> DevWorkspaceService.validateGitHost("github.com; rm -rf /"));
    }

    @Test
    @DisplayName("带端口的主机名通过")
    void acceptHostWithPort() {
        assertEquals("ghe.corp:8443", DevWorkspaceService.validateGitHost("ghe.corp:8443"));
    }
}
