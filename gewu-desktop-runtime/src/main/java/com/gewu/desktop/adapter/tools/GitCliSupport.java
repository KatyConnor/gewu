package com.gewu.desktop.adapter.tools;

import com.gewu.desktop.adapter.LocalFileService;
import com.gewu.desktop.security.PathGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * git 只读命令共享执行器（D1-18）：工作区根下运行 git CLI，输出截断 10KB。
 *
 * @since 1.0.0
 */
final class GitCliSupport {

    private static final int OUTPUT_CAP = 10_000;

    private GitCliSupport() {
    }

    private static java.util.List<String> concat(String first, String[] rest) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(first);
        cmd.addAll(java.util.Arrays.asList(rest));
        return cmd;
    }

    static String run(LocalFileService fileService, String sessionId, String subcommand) {
        try {
            Path root = fileService.rootFor(sessionId);
            PathGuard.secureResolve(root, "."); // 确认工作区可达
            ProcessBuilder pb = new ProcessBuilder(concat("git", subcommand.split("\\s+")));
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "git " + subcommand + " 超时";
            }
            String body = out.length() > OUTPUT_CAP ? out.substring(0, OUTPUT_CAP) + "\n…（已截断）" : out;
            return "exit=" + proc.exitValue() + "\n" + body;
        } catch (IOException e) {
            return "git 执行失败（可能未安装 git 或目录不是仓库）: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "git 执行被中断";
        }
    }
}
