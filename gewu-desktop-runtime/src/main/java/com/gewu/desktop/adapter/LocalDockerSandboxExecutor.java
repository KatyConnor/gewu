package com.gewu.desktop.adapter;

import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.desktop.config.DesktopProps;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 Docker 沙箱执行器（D2-1，ADR-008）：docker CLI 封装（引擎保持零依赖）。
 * <p><b>与平台 DockerSandboxProvider 行为对齐清单</b>：容器常驻（tail -f /dev/null 覆盖 CMD）、
 * 启动幂等、资源限额（1C/512M/无网络）、安全 opt（no-new-privileges/cap-drop）、
 * 卷挂载（工作区 → /workspace，会话文件工具与 code_execute 共享同一持久视图）、
 * 探活自愈（记录 running ≠ 容器存活 → 重建）。缺 Docker 时降级禁用（UI 外显）。
 *
 * @since 1.0.0
 */
@Component
public class LocalDockerSandboxExecutor implements SandboxExecutor {

    private static final String IMAGE = "alpine:latest";
    private static final long DOCKER_CHECK_TTL_MS = 60_000;

    private final LocalFileService fileService;
    private final DesktopProps props;

    private volatile Boolean dockerAvailable;
    private volatile long dockerCheckedAt;

    public LocalDockerSandboxExecutor(LocalFileService fileService, DesktopProps props) {
        this.fileService = fileService;
        this.props = props;
    }

    /** Docker 可用性（60s 缓存；无 Docker 降级外显） */
    public boolean isDockerAvailable() {
        long now = System.currentTimeMillis();
        if (dockerAvailable == null || now - dockerCheckedAt > DOCKER_CHECK_TTL_MS) {
            try {
                Process p = new ProcessBuilder("docker", "version", "--format", "ok")
                        .redirectErrorStream(true).start();
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                dockerAvailable = done && p.exitValue() == 0;
            } catch (Exception e) {
                dockerAvailable = false;
            }
            dockerCheckedAt = now;
        }
        return dockerAvailable;
    }

    // ==================== SandboxExecutor SPI ====================

    @Override
    public ExecResult executeCode(String language, String code, int timeoutSeconds) {
        return execute(language, code, timeoutSeconds, null);
    }

    /** 桌面扩展：带会话上下文（工作区卷挂载）。非 SPI 方法（SPI 仅三参形态）。 */
    public ExecResult executeForSession(String sessionId, String language, String code, int timeoutSeconds) {
        return execute(language, code, timeoutSeconds, sessionId);
    }

    private ExecResult execute(String language, String code, int timeoutSeconds, String sessionId) {
        if (!isDockerAvailable()) {
            return fail("本机 Docker 不可用，code_execute 已降级禁用（可改用 shell 工具或安装 Docker）");
        }
        int timeout = Math.min(Math.max(timeoutSeconds, 5), 300);
        try {
            String container = ensureContainer(sessionId);
            String script = buildScript(language, code);
            if (script == null) {
                return fail("镜像 " + IMAGE + " 不含 " + language + " 运行时（当前仅支持 shell；"
                        + "可更换含运行时的镜像，如 python:3-alpine）");
            }
            List<String> cmd = new ArrayList<>(List.of("docker", "exec", container, "sh", "-c", script));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            String err = new String(proc.getErrorStream().readAllBytes());
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                return fail("执行超时（" + timeout + "s）。已产生输出：\n" + cap(out), timeout);
            }
            ExecResult r = new ExecResult();
            r.setStdout(cap(out));
            r.setStderr(cap(err));
            r.setExitCode(proc.exitValue());
            r.setSuccess(proc.exitValue() == 0);
            return r;
        } catch (Exception e) {
            return fail("沙箱执行异常: " + e.getMessage(), timeout);
        }
    }

    // ==================== 容器生命周期（常驻 + 幂等 + 自愈） ====================

    private synchronized String ensureContainer(String sessionId) throws IOException, InterruptedException {
        String key = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        // 容器名纳入工作区绝对路径哈希：跨运行 cwd 变化时旧容器的固化挂载点不再复用
        Path workspace = fileService.rootFor(sessionId).toRealPath();
        String wsHash = Integer.toHexString((key + "|" + workspace).hashCode());
        String name = "gewu-dws-" + wsHash;
        // 探活（inspect 非零 = 容器不存在，属预期路径而非错误）
        try {
            List<String> state = docker("inspect", "-f", "{{.State.Running}}", name);
            if (!state.isEmpty() && "true".equals(state.get(0).trim())) {
                return name;
            }
            docker("start", name);
            return name;
        } catch (IOException absent) {
            // 容器不存在：走创建
        }
        // ADR-008 对齐：CMD 常驻/限额 1C-512M/无网络/安全 opt/卷挂载。
        // --user 以宿主 uid/gid 运行：cap-drop ALL 后容器 root 无 DAC_OVERRIDE，
        // 绑定挂载目录（宿主 uid 属主）的写入需 uid 对齐（用户实测 Permission denied）
        String uid = hostUid();
        String gid = hostGid();
        docker("run", "-d", "--name", name,
                "--user", uid + ":" + gid,
                "--memory", "512m", "--cpus", "1", "--network", "none",
                "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
                "--tmpfs", "/tmp:rw,size=64m",
                "-v", workspace + ":/workspace",
                "-w", "/workspace",
                IMAGE, "tail", "-f", "/dev/null");
        return name;
    }

    /** 宿主 uid/gid（缓存） */
    private String hostUid() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
        p.waitFor(5, TimeUnit.SECONDS);
        return p.getInputStream().readAllBytes().length > 0
                ? new String(p.getInputStream().readAllBytes()).trim() : "1000";
    }

    private String hostGid() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("id", "-g").redirectErrorStream(true).start();
        p.waitFor(5, TimeUnit.SECONDS);
        return new String(p.getInputStream().readAllBytes()).trim();
    }

    private List<String> docker(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            throw new IOException("docker " + args[0] + " 失败: " + out.trim());
        }
        return out.isBlank() ? List.of() : List.of(out.trim());
    }

    // ==================== 语言映射 ====================

    /** language → 容器内脚本；不支持的语言返回 null（诚实降级） */
    private String buildScript(String language, String code) {
        String lang = language == null ? "shell" : language.toLowerCase();
        return switch (lang) {
            case "shell", "bash", "sh" -> toShellScript(code);
            case "python", "python3" -> hasRuntime("python3")
                    ? toShellScript("python3 - <<'PYEOF'\n" + code + "\nPYEOF") : null;
            case "node", "javascript", "js" -> hasRuntime("node")
                    ? toShellScript("node -e " + shQuote(code)) : null;
            default -> toShellScript(code);
        };
    }

    private final AtomicLong probeCache = new AtomicLong(0);
    private final Map<String, Boolean> runtimeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 容器内运行时探测（60s 缓存） */
    private boolean hasRuntime(String bin) {
        long now = System.currentTimeMillis();
        if (now - probeCache.get() > 60_000) {
            runtimeCache.clear();
            probeCache.set(now);
        }
        return runtimeCache.computeIfAbsent(bin, b -> {
            try {
                String container = ensureContainer(null);
                Process p = new ProcessBuilder("docker", "exec", container, "which", b)
                        .redirectErrorStream(true).start();
                p.waitFor(10, TimeUnit.SECONDS);
                return p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static String toShellScript(String code) {
        // heredoc 形式承载多行脚本（引号安全）
        return "sh -s <<'GEWUEOF'\n" + code + "\nGEWUEOF";
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static ExecResult fail(String message, int timeout) {
        ExecResult r = new ExecResult();
        r.setStderr(message);
        r.setExitCode(-1);
        r.setSuccess(false);
        return r;
    }

    private static ExecResult fail(String message) {
        return fail(message, 0);
    }

    private static String cap(String s) {
        return s.length() > 10_000 ? s.substring(0, 10_000) + "\n…（输出已截断）" : s;
    }
}
