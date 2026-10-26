package com.gewu.sandbox.terminal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.mapper.SandboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页终端 PTY 会话服务（设计文档 44）：docker exec TTY 的 attach/resize/回收。
 * <p>一个 WS 连接 = 一个 PTY 会话；交互数据为原始字节流（控制帧 JSON 文本），
 * 不做逐条命令扫描（治理口径见设计文档 §F5：用户自有容器自有卷，
 * 信任级等同 dev 沙箱 /exec）。仅允许 attach 运行中的沙箱；
 * 每用户并发会话受限；会话空闲超时自动回收；心跳刷新 lastUsedAt 防误回收。
 */
@Slf4j
@Service
public class TerminalPtyService {

    private final DockerClient dockerClient;
    private final SandboxMapper sandboxMapper;
    private final ObjectMapper objectMapper;

    @Value("${gewu.sandbox.terminal.enabled:true}")
    boolean terminalEnabled;
    @Value("${gewu.sandbox.terminal.max-sessions-per-user:3}")
    int maxSessionsPerUser;
    @Value("${gewu.sandbox.terminal.idle-timeout-minutes:30}")
    int idleTimeoutMinutes;

    /** ptySessionId → 会话 */
    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();

    public TerminalPtyService(DockerClient dockerClient, SandboxMapper sandboxMapper,
                              ObjectMapper objectMapper) {
        this.dockerClient = dockerClient;
        this.sandboxMapper = sandboxMapper;
        this.objectMapper = objectMapper;
    }

    /** 一个活跃的终端会话 */
    static final class PtySession {
        final String id;
        final String sandboxId;
        final String userId;
        final String execId;
        final WebSocketSession ws;
        final PipedOutputStream stdin;
        volatile ExecStartResultCallback callback;
        volatile long lastActivity;

        PtySession(String id, String sandboxId, String userId, String execId,
                   WebSocketSession ws, PipedOutputStream stdin) {
            this.id = id;
            this.sandboxId = sandboxId;
            this.userId = userId;
            this.execId = execId;
            this.ws = ws;
            this.stdin = stdin;
            this.lastActivity = System.currentTimeMillis();
        }
    }

    /**
     * attach：在运行中的沙箱内创建交互式 bash（TTY），输出回推 WS。
     *
     * @return pty 会话 ID
     */
    public synchronized String attach(String sandboxId, String userId, int cols, int rows, WebSocketSession ws) {
        if (!terminalEnabled) {
            sendControl(ws, "error", "终端功能未开启");
            throw new IllegalStateException("terminal disabled");
        }
        long mine = sessions.values().stream().filter(s -> s.userId.equals(userId)).count();
        if (mine >= maxSessionsPerUser) {
            sendControl(ws, "error", "终端会话数已达上限（" + maxSessionsPerUser + "），请先关闭闲置终端");
            throw new IllegalStateException("too many sessions");
        }
        Sandbox sandbox = sandboxMapper.selectOne(new LambdaQueryWrapper<Sandbox>()
                .eq(Sandbox::getId, sandboxId).last("LIMIT 1"));
        if (sandbox == null || sandbox.getContainerId() == null || sandbox.getContainerId().isBlank()) {
            sendControl(ws, "error", "沙箱不存在或未就绪");
            throw new IllegalStateException("sandbox not found");
        }
        if (!"running".equalsIgnoreCase(sandbox.getStatus())) {
            sendControl(ws, "error", "沙箱未运行，请先启动");
            throw new IllegalStateException("sandbox not running");
        }

        try {
            // shell 探测：dev 镜像不保证有 bash（alpine 变体仅有 busybox ash），
            // 硬编码 bash 会在 "bash not found in $PATH" 上整条会话失败（用户实报）
            String shell = detectShell(sandbox.getContainerId());
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(sandbox.getContainerId())
                    .withCmd(shell, "-i")
                    .withTty(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withWorkingDir("/workspace")
                    .withEnv(java.util.List.of("TERM=xterm-256color", "LANG=C.UTF-8"))
                    .exec();

            PipedInputStream stdinIn = new PipedInputStream(64 * 1024);
            PipedOutputStream stdinOut = new PipedOutputStream(stdinIn);
            String ptyId = "pty-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            PtySession session = new PtySession(ptyId, sandboxId, userId, exec.getId(), ws, stdinOut);

            ExecStartResultCallback callback = new ExecStartResultCallback() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                    session.lastActivity = System.currentTimeMillis();
                    sendBinary(ws, frame.getPayload());
                }

                @Override
                public void onComplete() {
                    cleanup(session);
                    sendControl(ws, "exit", "终端进程已退出");
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("PTY 执行异常: ptyId={}, sandbox={}, err={}", ptyId, sandboxId, t.getMessage());
                    cleanup(session);
                    sendControl(ws, "error", "终端通道异常: " + t.getMessage());
                }
            };
            session.callback = callback;
            sessions.put(ptyId, session);
            // withStdIn：交互输入经管道持续写入（callback 生命周期内读取）
            dockerClient.execStartCmd(exec.getId()).withStdIn(stdinIn).exec(callback);
            // 初始尺寸：等 exec 进程真正启动后再设，失败仅降级（前端 Fit 会随布局重发 resize）
            try {
                callback.awaitStarted(2, java.util.concurrent.TimeUnit.SECONDS);
                dockerClient.resizeExecCmd(exec.getId()).withSize(rows, cols).exec();
            } catch (Exception e) {
                log.debug("初始 resize 失败（忽略）: {}", e.getMessage());
            }

            refreshActivity(sandboxId);
            sendControl(ws, "ready", ptyId);
            log.info("终端会话建立: ptyId={}, sandbox={}, user={}, shell={}, {}x{}", ptyId, sandboxId, userId, shell, cols, rows);
            return ptyId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            sendControl(ws, "error", "终端建立失败: " + e.getMessage());
            throw new RuntimeException("pty attach failed", e);
        }
    }

    /**
     * shell 探测：有 bash 用 bash（补全/历史体验完整），否则回退 /bin/sh（busybox ash）。
     */
    private String detectShell(String containerId) {
        try {
            ExecCreateCmdResponse probe = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "command -v bash")
                    .exec();
            ExecStartResultCallback cb = new ExecStartResultCallback();
            dockerClient.execStartCmd(probe.getId()).exec(cb)
                    .awaitCompletion(5, java.util.concurrent.TimeUnit.SECONDS);
            var inspect = dockerClient.inspectExecCmd(probe.getId()).exec();
            if (inspect.getExitCodeLong() != null && inspect.getExitCodeLong() == 0) {
                return "bash";
            }
        } catch (Exception e) {
            log.debug("bash 探测失败，回退 /bin/sh: {}", e.getMessage());
        }
        return "/bin/sh";
    }

    /** 尺寸变更（docker exec TTY resize） */
    public void resize(PtySession session, int cols, int rows) {
        try {
            dockerClient.resizeExecCmd(session.execId).withSize(rows, cols).exec();
        } catch (Exception e) {
            log.debug("resize 失败（忽略）: {}", e.getMessage());
        }
        session.lastActivity = System.currentTimeMillis();
    }

    /** WS 输入：原始字节写 PTY stdin */
    public void writeInput(PtySession session, byte[] payload) {
        try {
            session.stdin.write(payload);
            session.stdin.flush();
            session.lastActivity = System.currentTimeMillis();
            refreshActivity(session.sandboxId);
        } catch (Exception e) {
            log.debug("终端输入写入失败: {}", e.getMessage());
        }
    }

    /** 心跳保活 */
    public void touch(PtySession session) {
        session.lastActivity = System.currentTimeMillis();
        refreshActivity(session.sandboxId);
    }

    /** 关闭会话（WS 关闭/空闲/异常统一入口） */
    public synchronized void close(PtySession session, String reason) {
        if (sessions.remove(session.id) == null) {
            return;
        }
        cleanup(session);
        sendControl(session.ws, "closed", reason);
        log.info("终端会话关闭: ptyId={}, reason={}", session.id, reason);
    }

    public PtySession findByWs(WebSocketSession ws) {
        return sessions.values().stream()
                .filter(s -> s.ws.getId().equals(ws.getId()))
                .findFirst().orElse(null);
    }

    /** 空闲回收：超时未活动的会话主动关闭（容器仍按既有空闲策略 stop） */
    @Scheduled(fixedDelay = 60_000)
    public void sweepIdle() {
        long threshold = System.currentTimeMillis() - idleTimeoutMinutes * 60_000L;
        sessions.values().stream()
                .filter(s -> s.lastActivity < threshold)
                .forEach(s -> close(s, "idle_timeout"));
    }

    private void cleanup(PtySession session) {
        try {
            if (session.callback != null) {
                session.callback.close();
            }
        } catch (Exception ignore) {
            // 通道已断时的清理失败无需处理
        }
        try {
            session.stdin.close();
        } catch (Exception ignore) {
            // 同上
        }
    }

    private void refreshActivity(String sandboxId) {
        try {
            Sandbox sandbox = sandboxMapper.selectById(sandboxId);
            if (sandbox != null) {
                sandbox.setLastUsedAt(Instant.now().toEpochMilli());
                sandboxMapper.updateById(sandbox);
            }
        } catch (Exception e) {
            log.debug("刷新沙箱活跃时间失败: {}", e.getMessage());
        }
    }

    private void sendControl(WebSocketSession ws, String type, String detail) {
        if (ws == null || !ws.isOpen()) {
            return;
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", type);
            payload.put("message", detail == null ? "" : detail);
            synchronized (ws) {
                ws.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.debug("控制帧发送失败: type={}, err={}", type, e.getMessage());
        }
    }

    private void sendBinary(WebSocketSession ws, byte[] data) {
        if (ws == null || !ws.isOpen() || data == null || data.length == 0) {
            return;
        }
        try {
            synchronized (ws) {
                ws.sendMessage(new BinaryMessage(data));
            }
        } catch (Exception e) {
            log.debug("终端输出发送失败: {}", e.getMessage());
        }
    }
}
