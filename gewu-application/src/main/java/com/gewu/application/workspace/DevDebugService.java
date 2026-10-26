package com.gewu.application.workspace;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.dto.DebugStateDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发工作空间调试会话服务（IDEA 式 Debug·P1 Java + P3a Python/Node 多语言）。
 *
 * <p>架构：调试器进程经 FIFO 驱动（stdin=fifo，stdout=日志文件）常驻容器内；后端经既有
 * exec/writeFile 通道下发 {@code /workspace/.dwdbg/dbg.sh} 驱动脚本，所有交互 = 单层引用
 * 的短命令（send/poll/ready/applog/jlog），动作-响应模型天然适配 HTTP。
 *
 * <p>语言驱动（按运行命令前缀自动识别）：
 * <ul>
 *   <li><b>java</b>：目标 JVM 注入 JDWP agent（独立进程），jdb -attach 挂接；双日志
 *       （app.log=应用输出 / jdb.log=调试器输出）。</li>
 *   <li><b>python</b>：{@code python -m pdb}（调试器即被调试进程），单日志。</li>
 *   <li><b>node</b>：{@code node inspect}（内置 CLI 调试器），单日志。</li>
 *   <li><b>go</b>：{@code dlv debug}（delve REPL），单日志。</li>
 *   <li><b>c/c++/rust</b>：{@code gdb -q --args ./binary}（构建产物调试），单日志。</li>
 * </ul>
 * 调试器提示符（jdb "> " / pdb "(Pdb) " / node "debug> " / dlv "(dlv) " / gdb "(gdb) "）
 * 为命令完成信号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevDebugService {

    private final DevWorkspaceService devWorkspaceService;
    private final SandboxClient sandboxClient;

    private static final String DBG_DIR = "/workspace/.dwdbg";
    private static final String DBG_SH = ".dwdbg/dbg.sh";
    private static final int DEBUG_PORT = 5005;

    /** 容器内驱动脚本：全部 shell 复杂度收敛于此（writeFile 下发，Java 侧单层引用调用）。
     *  DPROMPT 环境变量参数化调试器提示符；isprompt 兼容 jdb 停点后的类上下文提示符变体（main[1] ）。 */
    private static final String DBG_SCRIPT = String.join("\n",
            "#!/bin/sh",
            "# 调试驱动脚本（DevDebugService 下发）",
            "DIR=/workspace/.dwdbg",
            "JLOG=$DIR/jdb.log",
            "DPROMPT=${DPROMPT:-\"> \"}",
            "# 提示符在场判定：固定 DPROMPT 尾配；java 附加变体（jdb 停点后提示符变为 'main[1] ' 类上下文形态）",
            "isprompt() {",
            "  T=$(tail -c 40 $JLOG 2>/dev/null | tr -d '\\r\\n')",
            "  case \"$T\" in",
            "    *\"$DPROMPT\") return 0 ;;",
            "  esac",
            "  if [ \"$DPROMPT\" = \"> \" ]; then",
            "    case \"$T\" in",
            "      *\"]\"|*\"] \")",
            "        if printf '%s' \"$T\" | grep -qE '(^|[^[])[A-Za-z0-9_$]+\\[[0-9]+\\] ?$'; then return 0; fi",
            "        ;;",
            "    esac",
            "  fi",
            "  return 1",
            "}",
            "case \"$1\" in",
            //  waitapp <sec>：等待被调试 JVM 的 JDWP 监听日志（仅 java 模式；返回 1/0）
            "  waitapp)",
            "    timeout ${2:-25} sh -c \"until grep -q 'Listening for transport' $DIR/app.log 2>/dev/null; do sleep 0.3; done\"",
            "    grep -c 'Listening for transport' $DIR/app.log 2>/dev/null || echo 0 ;;",
            //  send <cmd> <sec>：写入 FIFO 并等待提示符回归（超时=VM 运行中，命令仍已送达）
            "  send)",
            "    BEFORE=$(wc -c < $JLOG)",
            "    printf '%s\\n' \"$2\" > $DIR/in",
            "    N=$(( ${3:-15} * 5 )); i=0",
            "    while [ $i -lt $N ]; do",
            "      if isprompt && [ \"$(wc -c < $JLOG)\" -gt \"$BEFORE\" ]; then break; fi",
            "      sleep 0.2; i=$((i+1))",
            "    done",
            "    tail -c +$((BEFORE+1)) $JLOG | sed 's/\\x1b\\[[0-9;]*m//g' ;;",
            //  ready <sec>：等待调试器提示符（启动握手用）
            "  ready)",
            "    N=$(( ${2:-20} * 5 )); i=0",
            "    while [ $i -lt $N ]; do",
            "      if isprompt; then echo READY; exit 0; fi",
            "      sleep 0.2; i=$((i+1))",
            "    done",
            "    echo TIMEOUT ;;",
            //  poll <offset>：提示符在场则返回日志增量（断点异步命中探测）
            "  poll)",
            "    if isprompt; then tail -c +$(( $2 + 1 )) $JLOG | sed 's/\\x1b\\[[0-9;]*m//g'; fi ;;",
            //  applog <offset>：被调试应用控制台增量（java 模式独立 app.log）
            "  applog)",
            "    tail -c +$(( $2 + 1 )) $DIR/app.log 2>/dev/null ;;",
            //  jlog <offset>：调试日志增量（python/node 模式控制台=调试日志过滤提示符）
            "  jlog)",
            "    tail -c +$(( $2 + 1 )) $JLOG 2>/dev/null | sed 's/\\x1b\\[[0-9;]*m//g' ;;",
            "esac");

    private static final String PROMPT_JAVA = "> ";
    private static final String PROMPT_PYTHON = "(Pdb) ";
    private static final String PROMPT_NODE = "debug> ";
    private static final String PROMPT_GO = "(dlv) ";
    private static final String PROMPT_GDB = "(gdb) ";

    // ===== java（jdb）解析 =====
    /** 断点命中/步进完成行（新版 jdb "Main.main(), line=42" 与旧版 "Main.main(Main.java:42)" 两形态） */
    private static final Pattern HIT_NEW = Pattern.compile(
            "(Breakpoint hit|Step completed):\\s*\"thread=([^\"]+)\",\\s*([\\w.$]+)\\.([\\w$<>]+)\\(\\),\\s*line=(\\d+)");
    private static final Pattern HIT_OLD = Pattern.compile(
            "(Breakpoint hit|Step completed):\\s*\"thread=([^\"]+)\",\\s*([\\w.$]+)\\.([\\w$<>]+)\\((?:[\\w.$]+\\/)?([\\w.$]+\\.java):(\\d+)\\)");
    /** where 栈帧行："  [1] com.x.Main.main (Main.java:42)" */
    private static final Pattern FRAME_JAVA = Pattern.compile(
            "\\s*\\[\\d+\\]\\s+([\\w.$]+)\\.([\\w$<>]+)\\s*\\(([\\w.$]+\\.java)?(?::(\\d+))?\\)");
    /** locals 变量行："    name = value"（缩进 2+） */
    private static final Pattern LOCAL_JAVA = Pattern.compile("^\\s*(\\w[\\w.$]*)\\s*=\\s(.+)$");
    private static final Pattern CLASS_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

    // ===== python（pdb）解析 =====
    /** 命中/步进行："> /abs/path/file.py(42)func()"（--Return--/--Call-- 前缀内含同形） */
    private static final Pattern HIT_PY = Pattern.compile("> (\\S+\\.py)\\((\\d+)\\)([\\w<>]*)");
    /** w 栈帧行："  /path/file.py(42)func()->None" */
    private static final Pattern FRAME_PY = Pattern.compile("^\\s*([<>\\w./-]+\\.py)\\((\\d+)\\)([\\w<>]*)");
    /** p locals() 字典朴素解析："{'a': 1, 'b': 'x'}" */
    private static final Pattern LOCAL_PY = Pattern.compile("'([^']+)':\\s*([^,}]+)");

    // ===== node（node inspect）解析 =====
    /** 命中行："break in file:///workspace/.../main.js:5" */
    private static final Pattern HIT_NODE = Pattern.compile("break in (?:file://)?(\\S+):(\\d+)");
    /** bt 栈帧行："#0 fnName /path/file.js:5:13" */
    private static final Pattern FRAME_NODE = Pattern.compile("#\\d+\\s+(\\S*)\\s+(\\S+):(\\d+):\\d+");

    // ===== go（delve）解析 =====
    /** 命中行："> main.main() /workspace/.../main.go:10 (hits: 1 total: 1)" */
    private static final Pattern HIT_GO = Pattern.compile("> (\\S+)\\(\\) (\\S+\\.go):(\\d+)");
    /** stack 栈帧行：" 0  0x... in main.main at /workspace/.../main.go:10" */
    private static final Pattern FRAME_GO = Pattern.compile("\\d+\\s+0x\\S+ in (\\S+)\\s+at\\s+(\\S+):(\\d+)");
    /** locals 变量行："x = 5"（顶格） */
    private static final Pattern LOCAL_GO = Pattern.compile("^(\\w+)\\s*=\\s(.+)$");

    // ===== c/c++/rust（gdb）解析 =====
    /** 命中行："Breakpoint 1, main () at /workspace/.../main.c:10" */
    private static final Pattern HIT_GDB = Pattern.compile("Breakpoint \\d+, .+ at (\\S+):(\\d+)");
    /** bt 栈帧行："#0  main () at /workspace/.../main.c:10" / "#1  0x... in func () at f.c:5" */
    private static final Pattern FRAME_GDB = Pattern.compile("#\\d+\\s+(?:0x\\S+ in )?(\\S+)\\s*(?:\\([^)]*\\))?\\s*at\\s+(\\S+):(\\d+)");
    /** info locals 变量行："x = 5"（tab 缩进） */
    private static final Pattern LOCAL_GDB = Pattern.compile("^\\t?(\\w+)\\s*=\\s(.+)$");

    // ===== 线程/协程解析（jdb threads / dlv goroutines / gdb info threads） =====
    /** "  (java.lang.Thread)0x1  main                running" */
    private static final Pattern THREAD_JAVA = Pattern.compile("^\\s+\\(([^)]+)\\)(0x[0-9a-fA-F]+|\\d+)\\s+(\\S+)\\s+(\\S+)");
    /** "* Goroutine 1 - User: /path/main.go:10 main.main (0x...)" */
    private static final Pattern THREAD_GO = Pattern.compile("^(\\*\\s*)?Goroutine (\\d+) - User:\\s*(.*)$");
    /** "* 1    Thread 0x... (LWP 5) \"name\" 0x... in main () at main.c:10" */
    private static final Pattern THREAD_GDB = Pattern.compile("^(\\*\\s*)?(\\d+)\\s+Thread\\s+\\S+(?:\\s+\\(LWP \\d+\\))?(?:\\s+\"([^\"]*)\")?");

    /** 断点登记项（key = path:line） */
    private static class BreakpointInfo {
        final String className;
        final int line;
        /** 条件表达式（null/空=无条件；命中时求值 true 才挂起） */
        String condition;
        BreakpointInfo(String className, int line, String condition) {
            this.className = className;
            this.line = line;
            this.condition = condition;
        }
    }

    /** 用户会话（每用户一个；开发沙箱按用户隔离，固定端口不冲突） */
    private static class DebugSession {
        final String projectId;
        final String sandboxId;
        final String repoDir;
        /** 调试语言驱动：java / python / node */
        final String kind;
        /** path:line → BreakpointInfo */
        final Map<String, BreakpointInfo> breakpoints = new LinkedHashMap<>();
        /** Watch 表达式列表（挂起态逐个求值） */
        final List<String> watchExprs = new ArrayList<>();
        /** 运行到光标的临时断点（语言相关定位键：java=cls:line，python=abs:line，node=repoRel:line） */
        String tempBreakpoint;
        long jdbOffset = 0;
        long consoleOffset = 0;
        String status = "running";
        String message = "";
        String currentThread;
        DebugStateDTO.Frame topFrame;
        Instant createdAt = Instant.now();
        DebugSession(String projectId, String sandboxId, String repoDir, String kind) {
            this.projectId = projectId;
            this.sandboxId = sandboxId;
            this.repoDir = repoDir;
            this.kind = kind;
        }
    }

    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();

    // ==================== 语言驱动识别与命令变换 ====================

    static String kindOf(String runCmd) {
        String c = runCmd == null ? "" : runCmd.trim();
        if (c.startsWith("python3 ") || c.equals("python3") || c.startsWith("python ") || c.equals("python")) {
            return "python";
        }
        if (c.startsWith("node ") || c.equals("node")) {
            return "node";
        }
        if (c.startsWith("go ") || c.equals("go") || c.startsWith("dlv ")) {
            return "go";
        }
        // ./binary 形式（gcc/g++/rustc 构建产物）；gradlew wrapper 除外（java 系）
        if ((c.startsWith("./") && !c.startsWith("./gradlew")) || c.startsWith("gdb ") || c.startsWith("rust-gdb ")) {
            return "c";
        }
        return "java";
    }

    private static String promptOf(String kind) {
        return switch (kind) {
            case "python" -> PROMPT_PYTHON;
            case "node" -> PROMPT_NODE;
            case "go" -> PROMPT_GO;
            case "c" -> PROMPT_GDB;
            default -> PROMPT_JAVA;
        };
    }

    /** JDWP 注入：java 直插 agent；mvn 走 spring-boot.run.jvmArguments；gradle 走 --debug-jvm；其余 JAVA_TOOL_OPTIONS */
    static String injectJdwp(String runCmd, String suspend) {
        String agent = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + suspend + ",address=*:" + DEBUG_PORT;
        String c = runCmd.trim();
        if (c.startsWith("java ")) {
            return "java " + agent + " " + c.substring(5);
        }
        if (c.equals("java")) {
            return "java " + agent;
        }
        if (c.startsWith("mvn ")) {
            return c + " -Dspring-boot.run.jvmArguments=" + q(agent);
        }
        if (c.equals("mvn")) {
            return "mvn spring-boot:run -Dspring-boot.run.jvmArguments=" + q(agent);
        }
        if (c.startsWith("gradle ")) {
            return c + " --debug-jvm";
        }
        return "JAVA_TOOL_OPTIONS=" + q(agent) + " " + c;
    }

    /** python → python -m pdb（pdb 即调试器进程） */
    static String toPdb(String runCmd) {
        String c = runCmd.trim();
        if (c.startsWith("python3")) {
            return "python3 -m pdb " + c.substring("python3".length()).trim();
        }
        return "python -m pdb " + c.substring("python".length()).trim();
    }

    /** node → node inspect（内置 CLI 调试器） */
    static String toNodeInspect(String runCmd) {
        return "node inspect " + runCmd.trim().substring("node".length()).trim();
    }

    /** go run → dlv debug（包路径形式；.go 文件形式不支持——delve 需包路径） */
    static String toDlv(String runCmd) {
        String c = runCmd.trim();
        if (c.startsWith("dlv ")) {
            return c; // 已是 delve 命令，透传
        }
        String arg = c.substring("go run".length()).trim();
        if (arg.endsWith(".go")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "Go 调试需包路径形式（如 go run . / go run ./cmd/app），不支持直接调试 .go 文件");
        }
        return "dlv debug --allow-non-terminal-interactive=true" + (arg.isEmpty() ? "" : " " + arg);
    }

    /** ./binary → gdb -q --args（gcc/g++/rustc 构建产物直接调试） */
    static String toGdb(String runCmd) {
        String c = runCmd.trim();
        if (c.startsWith("gdb ") || c.startsWith("rust-gdb ")) {
            return c; // 已是 gdb 命令，透传
        }
        return "gdb -q --args " + c;
    }

    // ==================== 生命周期 ====================

    /** 启动调试：按语言驱动分支（java=JDWP+jdb 双进程；python/node=调试器即进程） */
    public DebugStateDTO start(String projectId, String command, Boolean suspend, String mode) {
        com.gewu.domain.project.Project project = devWorkspaceService.requireGitProject(projectId);
        String userId = requireUserId();
        stopInternal(userId);

        String sid = devWorkspaceService.ensureRunningSandboxId();
        String repoDir = "/workspace/" + DevWorkspaceService.sanitizeWorkspaceRel(project.getRepoLocalPath());
        String runCmd = command != null && !command.isBlank() ? command.trim() : "mvn spring-boot:run";
        String kind = kindOf(runCmd);
        boolean attach = "attach".equals(mode);
        if (attach && !"java".equals(kind)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "附加模式仅支持 Java（其余语言请用 launch 模式）");
        }

        // 清场（残留调试进程/FIFO）+ 建目录 + 下发驱动脚本
        // 注意：kill 与清理分两次 exec——pkill -f 模式会匹配到自身命令行把同 shell 后续语句连带杀掉
        sh(sid, "pkill -f 'agentlib:jdwp' 2>/dev/null; pkill -f 'jdb -attach' 2>/dev/null; "
                + "pkill -f 'python -m pdb' 2>/dev/null; pkill -f 'node inspect' 2>/dev/null; pkill -f 'dlv debug' 2>/dev/null; pkill -f 'gdb -q' 2>/dev/null; exit 0", 15);
        sh(sid, "rm -rf " + q(DBG_DIR) + "; mkdir -p " + q(DBG_DIR), 10);
        try {
            sandboxClient.writeFile(sid, DBG_SH, DBG_SCRIPT);
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "调试脚本下发失败: " + e.getMessage());
        }
        sh(sid, "chmod +x " + q(DBG_DIR + "/dbg.sh"), 5);

        if ("java".equals(kind)) {
            startJava(userId, sid, projectId, repoDir, runCmd, Boolean.TRUE.equals(suspend) ? "y" : "n", attach);
        } else {
            startSingleProcess(userId, sid, projectId, repoDir, runCmd, kind);
        }
        log.info("调试会话启动: userId={}, projectId={}, kind={}, cmd={}", userId, projectId, kind, runCmd);
        return state(projectId);
    }

    private void startJava(String userId, String sid, String projectId, String repoDir, String runCmd, String suspend, boolean attach) {
        if (attach) {
            // 附加模式：应用已运行且开启调试代理——仅校验端口监听（/proc/net/tcp 探测，依赖镜像内 netstat 与否均可）
            String port = sh(sid, "awk 'NR>1 && $4==\"0A\" && ($2 ~ /:1389$/)' /proc/net/tcp /proc/net/tcp6 2>/dev/null | wc -l", 10);
            if (!port.trim().startsWith("1")) {
                stopInternal(userId);
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "未检测到调试端口监听（5005）。请先用「运行」以调试代理参数启动应用，例如：\n"
                        + "mvn spring-boot:run -Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
            }
        } else {
            String injected = injectJdwp(runCmd, suspend);
            // 启动被调试 JVM（脱离会话长驻，日志重定向）
            sh(sid, "cd " + q(repoDir) + " && setsid nohup bash -c " + q(injected)
                    + " > " + q(DBG_DIR + "/app.log") + " 2>&1 < /dev/null & echo started", 15);
            // 等待 JDWP 监听就绪
            String up = dbg(sid, "java", "waitapp 25");
            if (!up.trim().endsWith("1")) {
                String tailLog = sh(sid, "tail -c 800 " + q(DBG_DIR + "/app.log") + " 2>/dev/null", 5);
                stopInternal(userId);
                throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                        "调试进程未启动（未检测到 JDWP 监听）。命令: " + runCmd + "\n" + tailLog.trim());
            }
        }
        // FIFO + 保持写端（防 EOF）+ jdb attach 常驻
        sh(sid, "mkfifo " + q(DBG_DIR + "/in") + "; (sleep 86400 > " + q(DBG_DIR + "/in") + " &); "
                + "setsid nohup jdb -attach localhost:" + DEBUG_PORT
                + " < " + q(DBG_DIR + "/in") + " > " + q(DBG_DIR + "/jdb.log") + " 2>&1 & echo jdb-started", 15);
        String ready = dbg(sid, "java", "ready 20");
        if (!ready.contains("READY")) {
            String tailJdb = sh(sid, "tail -c 500 " + q(DBG_DIR + "/jdb.log") + " 2>/dev/null", 5);
            stopInternal(userId);
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "jdb 连接调试进程失败。\n" + tailJdb.trim());
        }
        sessions.put(userId, new DebugSession(projectId, sid, repoDir, "java"));
    }

    /** 单进程调试器驱动（pdb/node inspect/dlv/gdb）：stdin=FIFO，stdout 全量进调试日志 */
    private void startSingleProcess(String userId, String sid, String projectId, String repoDir, String runCmd, String kind) {
        String transformed = switch (kind) {
            case "python" -> toPdb(runCmd);
            case "node" -> toNodeInspect(runCmd);
            case "go" -> toDlv(runCmd);
            default -> toGdb(runCmd);
        };
        // 调试器即被调试进程：stdin=FIFO，stdout 全量进调试日志（程序输出与调试输出同文件）
        sh(sid, "cd " + q(repoDir) + " && mkfifo " + q(DBG_DIR + "/in") + "; (sleep 86400 > " + q(DBG_DIR + "/in") + " &); "
                + "setsid nohup bash -c " + q(transformed)
                + " < " + q(DBG_DIR + "/in") + " > " + q(DBG_DIR + "/jdb.log") + " 2>&1 & echo debugger-started", 15);
        String ready = dbg(sid, kind, "ready 25");
        if (!ready.contains("READY")) {
            String tail = sh(sid, "tail -c 500 " + q(DBG_DIR + "/jdb.log") + " 2>/dev/null", 5);
            stopInternal(userId);
            throw BusinessException.of(ResultCode.SYSTEM_ERROR,
                    "调试器启动失败（" + kind + "）。命令: " + transformed + "\n" + tail.trim());
        }
        sessions.put(userId, new DebugSession(projectId, sid, repoDir, kind));
        // pdb/node/dlv 启动即停首行；gdb（--args 载入未运行）需 run 启动——自动 continue 进入运行态
        DebugSession session = sessions.get(userId);
        send(session, "c".equals(kind) ? "run" : opCmd(session, "cont"), "go".equals(kind) ? 15 : 8);
    }

    /** 停止调试：结束调试器与目标进程，清理容器内调试目录 */
    public DebugStateDTO stop(String projectId) {
        devWorkspaceService.requireGitProject(projectId);
        stopInternal(requireUserId());
        DebugStateDTO dto = new DebugStateDTO();
        dto.setStatus("inactive");
        return dto;
    }

    private void stopInternal(String userId) {
        DebugSession s = sessions.remove(userId);
        if (s == null) {
            return;
        }
        try {
            sh(s.sandboxId, "pkill -f 'agentlib:jdwp' 2>/dev/null; pkill -f 'jdb -attach' 2>/dev/null; "
                    + "pkill -f 'python -m pdb' 2>/dev/null; pkill -f 'node inspect' 2>/dev/null; pkill -f 'dlv debug' 2>/dev/null; pkill -f 'gdb -q' 2>/dev/null; exit 0", 15);
            sh(s.sandboxId, "rm -rf " + q(DBG_DIR), 10);
        } catch (Exception e) {
            log.warn("调试会话清理失败: {}", e.getMessage());
        }
    }

    // ==================== 断点 ====================

    public DebugStateDTO breakpoint(String projectId, String path, Integer line, Boolean remove, String condition) {
        DebugSession session = sessionOf(projectId);
        if (line == null || line < 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "断点行号非法");
        }
        String key = path + ":" + line;
        synchronized (session) {
            if (Boolean.TRUE.equals(remove)) {
                session.breakpoints.remove(key);
                session.message = send(session, bpRemoveCmd(session, path, line), 8).trim();
            } else {
                String out = send(session, bpAddCmd(session, path, line), 8).trim();
                BreakpointInfo bp = session.breakpoints.computeIfAbsent(key,
                        k -> new BreakpointInfo(classLabelOf(session, path), line, null));
                // 条件表达式随断点设置/更新（空串=清除条件）
                bp.condition = condition != null && !condition.isBlank() ? condition.trim() : null;
                session.message = (out.isEmpty() ? "断点已登记（类未加载时延迟生效）" : out)
                        + (bp.condition != null ? "（条件: " + bp.condition + "）" : "");
            }
        }
        return state(projectId);
    }

    /** 断点设置命令（按语言驱动） */
    private String bpAddCmd(DebugSession session, String path, int line) {
        switch (session.kind) {
            case "python":
                return "b " + q(absOf(session, path)) + ":" + line;
            case "node":
                return "sb(" + q(repoRelOf(session, path)) + ", " + line + ")";
            case "go":
                return "break " + q(absOf(session, path)) + ":" + line;
            case "c":
                return "break " + q(absOf(session, path)) + ":" + line;
            default:
                return "stop at " + classOf(path) + ":" + line;
        }
    }

    private String bpRemoveCmd(DebugSession session, String path, int line) {
        switch (session.kind) {
            case "python":
                return "clear " + q(absOf(session, path)) + ":" + line;
            case "node":
                return "cb(" + q(repoRelOf(session, path)) + ", " + line + ")";
            case "go":
                return "clearall " + q(absOf(session, path)) + ":" + line;
            case "c":
                return "clear " + q(absOf(session, path)) + ":" + line;
            default:
                return "clear " + classOf(path) + ":" + line;
        }
    }

    /** 断点登记的展示类名（java=FQCN；python=模块点路径；node/go/c=文件路径） */
    private String classLabelOf(DebugSession session, String path) {
        if ("python".equals(session.kind)) {
            String rel = repoRelOf(session, path);
            return rel.endsWith(".py") ? rel.substring(0, rel.length() - 3).replace('/', '.') : rel;
        }
        if (!"java".equals(session.kind)) {
            return repoRelOf(session, path);
        }
        return classOf(path);
    }

    // ==================== Watches ====================

    /** Watch 表达式登记/移除（挂起态逐个求值回填） */
    public DebugStateDTO watch(String projectId, String expr, Boolean remove) {
        DebugSession session = sessionOf(projectId);
        if (expr == null || expr.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "Watch 表达式不能为空");
        }
        String e = expr.trim();
        synchronized (session) {
            if (Boolean.TRUE.equals(remove)) {
                session.watchExprs.remove(e);
            } else if (!session.watchExprs.contains(e)) {
                session.watchExprs.add(e);
            }
        }
        return state(projectId);
    }

    /** 挂起态求值全部 Watch（输出去掉表达式回显前缀） */
    private List<DebugStateDTO.Watch> snapshotWatches(DebugSession session) {
        List<DebugStateDTO.Watch> list = new ArrayList<>();
        for (String expr : session.watchExprs) {
            DebugStateDTO.Watch w = new DebugStateDTO.Watch();
            w.setExpr(expr);
            String out = send(session, evalCmd(session, expr), 6).trim();
            String prefix = switch (session.kind) {
                case "python" -> ""; // pdb 直接输出值
                default -> expr;    // jdb "expr = value" / node exec 直接输出
            };
            if (!prefix.isEmpty() && out.startsWith(expr) && out.length() > expr.length()) {
                w.setValue(out.substring(expr.length()).replaceFirst("^\\s*=\\s*", ""));
            } else {
                w.setValue(truncate(out, 200));
            }
            list.add(w);
        }
        return list;
    }

    // ==================== 执行控制 ====================

    /** op: cont/next/step/stepUp/runTo/thread（runTo=临时断点运行到指定行；thread=切换调试上下文线程） */
    public DebugStateDTO action(String projectId, String op, String path, Integer line, String threadId) {
        DebugSession session = sessionOf(projectId);
        synchronized (session) {
            String cmd;
            int waitSec;
            boolean tempBp = false;
            switch (op == null ? "" : op) {
                case "cont" -> { cmd = opCmd(session, "cont"); waitSec = 25; }
                case "next" -> { cmd = opCmd(session, "next"); waitSec = 12; }
                case "step" -> { cmd = opCmd(session, "step"); waitSec = 12; }
                case "stepUp" -> { cmd = opCmd(session, "stepUp"); waitSec = 12; }
                case "runTo" -> {
                    if (line == null || line < 1) {
                        throw BusinessException.of(ResultCode.PARAM_INVALID, "运行到光标需要有效行号");
                    }
                    send(session, bpAddCmd(session, path, line), 8);
                    session.tempBreakpoint = tempKeyOf(session, path, line);
                    cmd = opCmd(session, "cont");
                    waitSec = 25;
                    tempBp = true;
                }
                case "thread" -> {
                    // 线程切换（IDEA Threads 视图选中）：切换后 refreshSuspended 取新上下文栈/变量
                    if (threadId == null || threadId.isBlank()) {
                        throw BusinessException.of(ResultCode.PARAM_INVALID, "线程切换需要线程标识");
                    }
                    cmd = threadSwitchCmd(session, threadId);
                    waitSec = 8;
                }
                default -> throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的操作: " + op);
            }
            String out = send(session, cmd, waitSec);
            if (out.isBlank()) {
                // 提示符未回归 = VM 运行中；不再追加栈/变量命令防止 FIFO 命令积压
                session.status = "running";
                session.message = tempBp ? "运行中，等待到达目标行…" : "";
                session.topFrame = null;
            } else {
                applyHit(session, out);
                enforceConditions(session);
                // 无条件刷新栈快照：gdb 步进无事件行（仅源码行回显），停点须从 bt 重新推导
                refreshSuspended(session);
                if ("thread".equals(op)) {
                    session.currentThread = threadId;
                    session.message = "已切换线程: " + threadId;
                } else if (!out.trim().isEmpty()) {
                    session.message = out.trim();
                }
            }
        }
        return state(projectId);
    }

    /** 执行控制命令映射（步过/步入/步出按语言） */
    private String opCmd(DebugSession session, String op) {
        switch (session.kind) {
            case "python":
                return switch (op) {
                    case "cont" -> "c";
                    case "next" -> "n";
                    case "step" -> "s";
                    default -> "r"; // 步出=运行至当前函数返回
                };
            case "node":
                return switch (op) {
                    case "cont" -> "c";
                    case "next" -> "n";
                    case "step" -> "s";
                    default -> "o"; // 步出
                };
            case "go":
                return switch (op) {
                    case "cont" -> "continue";
                    case "next" -> "next";
                    case "step" -> "step";
                    default -> "stepout";
                };
            case "c":
                return switch (op) {
                    case "cont" -> "continue";
                    case "next" -> "next";
                    case "step" -> "step";
                    default -> "finish";
                };
            default:
                return switch (op) {
                    case "cont" -> "cont";
                    case "next" -> "next";
                    case "step" -> "step";
                    default -> "step up";
                };
        }
    }

    private String evalCmd(DebugSession session, String expr) {
        switch (session.kind) {
            case "python": return "p " + expr;
            case "node": return "exec " + expr;
            case "go": return "print " + expr;
            case "c": return "print " + expr;
            default: return "print " + expr;
        }
    }

    private String stackCmd(DebugSession session) {
        switch (session.kind) {
            case "python": return "w";
            case "node": return "bt";
            case "go": return "stack";
            case "c": return "bt";
            default: return "where";
        }
    }

    /** 线程清单命令（python 单线程无此视图） */
    private String threadsCmd(DebugSession session) {
        switch (session.kind) {
            case "go": return "goroutines";
            case "c": return "info threads";
            default: return "threads";
        }
    }

    private String threadSwitchCmd(DebugSession session, String threadId) {
        return switch (session.kind) {
            case "go" -> "goroutine " + threadId;
            default -> "thread " + threadId; // jdb/gdb 同形
        };
    }

    /** 挂起态线程清单解析（python 返回空；当前线程按 * 标记或会话记录） */
    private List<DebugStateDTO.Thread> parseThreads(DebugSession session, String out) {
        List<DebugStateDTO.Thread> list = new ArrayList<>();
        if ("python".equals(session.kind)) {
            return list;
        }
        for (String line : out.split("\n")) {
            if (list.size() >= 50) {
                break;
            }
            switch (session.kind) {
                case "go" -> {
                    Matcher m = THREAD_GO.matcher(line);
                    if (m.matches()) {
                        DebugStateDTO.Thread t = new DebugStateDTO.Thread();
                        t.setId(m.group(2));
                        t.setName("Goroutine " + m.group(2));
                        t.setCurrent(m.group(1) != null
                                || (session.currentThread != null && session.currentThread.equals(m.group(2))));
                        t.setStatusDesc(truncate(m.group(3).trim(), 120));
                        list.add(t);
                    }
                }
                case "c" -> {
                    Matcher m = THREAD_GDB.matcher(line);
                    if (m.matches()) {
                        DebugStateDTO.Thread t = new DebugStateDTO.Thread();
                        t.setId(m.group(2));
                        t.setName(m.group(3) != null && !m.group(3).isBlank() ? m.group(3) : "Thread " + m.group(2));
                        t.setCurrent(m.group(1) != null
                                || (session.currentThread != null && session.currentThread.equals(m.group(2))));
                        list.add(t);
                    }
                }
                default -> {
                    Matcher m = THREAD_JAVA.matcher(line);
                    if (m.matches()) {
                        DebugStateDTO.Thread t = new DebugStateDTO.Thread();
                        t.setId(m.group(2));
                        t.setName(m.group(3));
                        t.setStatusDesc(m.group(4));
                        t.setCurrent(session.currentThread != null
                                ? session.currentThread.equalsIgnoreCase(m.group(2))
                                : "main".equals(m.group(3)));
                        list.add(t);
                    }
                }
            }
        }
        return list;
    }

    /** 表达式求值（顶层栈帧，IDEA Alt+F8） */
    public DebugStateDTO eval(String projectId, String expr) {
        DebugSession session = sessionOf(projectId);
        if (expr == null || expr.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "求值表达式不能为空");
        }
        synchronized (session) {
            String out = send(session, evalCmd(session, expr.trim()), 10).trim();
            if ("java".equals(session.kind) && out.startsWith(expr.trim())) {
                out = out.substring(expr.trim().length()).replaceFirst("^\\s*=\\s*", "");
            }
            session.message = out;
        }
        return state(projectId);
    }

    /**
     * 条件断点过滤（IDEA 条件断点复刻）：挂起点命中带条件的断点时，在顶层栈帧求值条件，
     * 为 false/不可求值则自动继续，直到条件成立、换到其他停点或超限。
     * 上限：30 次迭代 / 约 20 秒（前端 axios 30s 超时内）。
     */
    private void enforceConditions(DebugSession session) {
        long deadline = System.currentTimeMillis() + 20_000;
        int iter = 0;
        while (iter++ < 30 && System.currentTimeMillis() < deadline && "stopped".equals(session.status)) {
            DebugStateDTO.Frame top = session.topFrame;
            if (top == null || top.getLine() == null) {
                return;
            }
            BreakpointInfo bp = findBreakpointAt(session, top);
            if (bp == null) {
                return; // 当前停点无条件，正常挂起
            }
            String out = send(session, evalCmd(session, bp.condition), 6).trim();
            if (isTruthy(session.kind, out)) {
                session.message = "条件成立: " + bp.condition;
                return; // 条件成立，保持挂起
            }
            // 条件不成立或不可求值（异常/字段未加载）→ 自动继续
            String contOut = send(session, opCmd(session, "cont"), 8);
            if (contOut.isBlank()) {
                session.status = "running";
                session.topFrame = null;
                session.message = "条件未成立（" + bp.condition + "），继续运行…";
                return;
            }
            applyHit(session, contOut);
        }
    }

    private BreakpointInfo findBreakpointAt(DebugSession session, DebugStateDTO.Frame top) {
        return session.breakpoints.values().stream()
                .filter(b -> b.condition != null && matchesStop(session, b, top))
                .findFirst().orElse(null);
    }

    /** 断点与停点位置匹配（java=类名+行；python=模块路径+行；node/go/c=文件+行） */
    private boolean matchesStop(DebugSession session, BreakpointInfo bp, DebugStateDTO.Frame top) {
        if ("java".equals(session.kind)) {
            return bp.className.equals(top.getClassName()) && bp.line == top.getLine();
        }
        String rel = top.getFileName() != null ? top.getFileName() : "";
        boolean fileMatch = "python".equals(session.kind)
                ? rel.replace('/', '.').equals(bp.className)
                : rel.equals(bp.className);
        return fileMatch && bp.line == top.getLine();
    }

    /** 求值输出真值判定：java "= true" / python "True" / node/go "true" / gdb "$N = 非零" */
    private static boolean isTruthy(String kind, String out) {
        return switch (kind) {
            case "python" -> out.equals("True");
            case "node", "go" -> out.equals("true");
            case "c" -> out.matches("\\$\\d+\\s*=\\s*0\\s*$") ? false
                    : out.matches("\\$\\d+\\s*=\\s*.+");
            default -> out.toLowerCase(Locale.ROOT).endsWith("= true");
        };
    }

    // ==================== 状态 ====================

    public DebugStateDTO state(String projectId) {
        String userId = requireUserId();
        DebugSession session = sessions.get(userId);
        DebugStateDTO dto = new DebugStateDTO();
        if (session == null || !session.projectId.equals(projectId)) {
            dto.setStatus("inactive");
            return dto;
        }
        synchronized (session) {
            dto.setKind(session.kind);
            // 调试日志增量：断点异步命中（cont 超时后命中）在此被捕获
            String delta = dbg(session.sandboxId, session.kind, "poll " + session.jdbOffset);
            if (!delta.isEmpty()) {
                session.jdbOffset += delta.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                applyHit(session, delta);
                // 异步命中的条件断点同样走条件过滤（false 自动继续）
                enforceConditions(session);
            }
            // 应用控制台增量：java=独立 app.log；python/node=调试日志过滤提示符与停点标记
            String console;
            if ("java".equals(session.kind)) {
                console = dbg(session.sandboxId, session.kind, "applog " + session.consoleOffset);
                session.consoleOffset += console.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            } else {
                String raw = dbg(session.sandboxId, session.kind, "jlog " + session.consoleOffset);
                session.consoleOffset += raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                console = filterConsole(session.kind, raw);
            }
            // 终结探测
            if ("java".equals(session.kind)
                    && (delta.contains("The application has been disconnected")
                        || delta.contains("The application exited"))) {
                session.status = "terminated";
            } else if (!"java".equals(session.kind) && delta.isEmpty()) {
                checkTerminated(session);
            }

            dto.setStatus(session.status);
            dto.setMessage(session.message);
            dto.setConsole(console);
            DebugStateDTO.Frame top = session.topFrame;
            if (top != null) {
                dto.setCurrentClass(top.getClassName());
                dto.setCurrentMethod(top.getMethodName());
                dto.setCurrentFile(top.getFileName());
                dto.setCurrentLine(top.getLine());
            }
            session.breakpoints.forEach((key, info) -> {
                DebugStateDTO.Breakpoint bp = new DebugStateDTO.Breakpoint();
                int idx = key.lastIndexOf(':');
                bp.setPath(key.substring(0, idx));
                bp.setLine(Integer.parseInt(key.substring(idx + 1)));
                bp.setClassName(info.className);
                bp.setCondition(info.condition);
                dto.getBreakpoints().add(bp);
            });
            // 挂起态补齐栈/变量/Watches/线程快照
            if ("stopped".equals(session.status)) {
                String where = send(session, stackCmd(session), 6);
                dto.getFrames().addAll(parseFrames(session, where));
                switch (session.kind) {
                    case "java" -> dto.getLocals().addAll(parseLocalsJava(send(session, "locals", 6)));
                    case "python" -> dto.getLocals().addAll(parseLocalsPython(send(session, "p locals()", 6)));
                    case "go" -> dto.getLocals().addAll(parseLocalsGo(send(session, "locals", 6)));
                    case "c" -> dto.getLocals().addAll(parseLocalsGdb(send(session, "info locals", 6)));
                    default -> { /* node 无局部变量枚举，用求值 */ }
                }
                dto.getWatches().addAll(snapshotWatches(session));
                dto.getThreads().addAll(parseThreads(session, send(session, threadsCmd(session), 10)));
                dto.setCurrentThread(session.currentThread);
            }
        }
        return dto;
    }

    /** python/node/go/c 终结探测：日志尾部出现退出标记 */
    private void checkTerminated(DebugSession session) {
        if (!"running".equals(session.status)) {
            return;
        }
        String tail = sh(session.sandboxId, "tail -c 300 " + q(DBG_DIR + "/jdb.log") + " 2>/dev/null", 5);
        boolean done = switch (session.kind) {
            case "python" -> tail.contains("The program finished");
            case "node" -> tail.contains("debugger to disconnect") || tail.contains("Debugging ended");
            case "go" -> tail.contains("has exited with status") || tail.contains("Process exiting");
            case "c" -> tail.contains("exited normally") || tail.contains("exited with code");
            default -> false;
        };
        if (done) {
            session.status = "terminated";
        }
    }

    /** 控制台过滤（python/node/go/c）：剥离调试器提示符与停点标记行 */
    private static String filterConsole(String kind, String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.equals("(Pdb)") || t.equals("debug>") || t.equals("(dlv)") || t.equals("(gdb)")) {
                continue;
            }
            if ("python".equals(kind) && HIT_PY.matcher(line).find()) {
                continue;
            }
            if ("node".equals(kind) && HIT_NODE.matcher(line).find()) {
                continue;
            }
            if ("go".equals(kind) && HIT_GO.matcher(line).find()) {
                continue;
            }
            if ("c".equals(kind) && HIT_GDB.matcher(line).find()) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 挂起后刷新停点信息（栈快照取顶帧行） */
    private void refreshSuspended(DebugSession session) {
        String where = send(session, stackCmd(session), 6);
        List<DebugStateDTO.Frame> frames = parseFrames(session, where);
        if (!frames.isEmpty()) {
            session.topFrame = frames.get(0);
            session.status = "stopped";
        } else {
            // 无栈帧：VM 运行中（jdb 运行态 where 报 No thread）或已退出——终止只由退出标记判定
            session.topFrame = null;
            if (!"java".equals(session.kind) && (where.toLowerCase().contains("not being run")
                    || where.toLowerCase().contains("not launched"))) {
                session.status = "terminated";
            } else {
                session.status = "running";
            }
        }
    }

    /** 增量输出中解析停点 → 更新顶帧；命中「运行到光标」临时断点即清除 */
    private void applyHit(DebugSession session, String out) {
        DebugStateDTO.Frame f = switch (session.kind) {
            case "python" -> parseHitPython(session, out);
            case "node" -> parseHitNode(session, out);
            case "go" -> parseHitGo(session, out);
            case "c" -> parseHitGdb(session, out);
            default -> parseHitJava(out);
        };
        if (f == null) {
            return;
        }
        // java 命中行携带当前线程（"thread=main"）——线程视图默认上下文
        if ("java".equals(session.kind)) {
            Matcher tm = Pattern.compile("\"thread=([^\"]+)\"").matcher(out);
            if (tm.find()) {
                session.currentThread = tm.group(1);
            }
        }
        session.topFrame = f;
        session.status = "stopped";
        // 运行到光标的临时断点：到达即拆（stop/clear 或 b/clear 或 sb/cb 配对）
        if (session.tempBreakpoint != null && session.tempBreakpoint.equals(tempKeyOfFrame(session, f))) {
            send(session, tempClearCmd(session, session.tempBreakpoint), 6);
            session.tempBreakpoint = null;
        }
    }

    private DebugStateDTO.Frame parseHitJava(String out) {
        Matcher m = HIT_NEW.matcher(out);
        String cls = null, method = null, line = null, file = null;
        while (m.find()) {
            cls = m.group(3);
            method = m.group(4);
            line = m.group(5);
        }
        if (cls == null) {
            m = HIT_OLD.matcher(out);
            while (m.find()) {
                cls = m.group(3);
                method = m.group(4);
                file = m.group(5);
                line = m.group(6);
            }
        }
        if (cls == null) {
            return null;
        }
        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
        f.setClassName(cls);
        f.setMethodName(method);
        f.setFileName(file != null ? file : cls.substring(cls.lastIndexOf('.') + 1) + ".java");
        try {
            f.setLine(Integer.parseInt(line));
        } catch (NumberFormatException ignored) {
        }
        return f;
    }

    private DebugStateDTO.Frame parseHitPython(DebugSession session, String out) {
        Matcher m = HIT_PY.matcher(out);
        String file = null, line = null, func = null;
        while (m.find()) {
            file = m.group(1);
            line = m.group(2);
            func = m.group(3);
        }
        if (file == null) {
            return null;
        }
        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
        f.setFileName(relOrAbs(session, file));
        f.setClassName(moduleLabel(session, file));
        f.setMethodName(func == null || func.isEmpty() ? "<module>" : func);
        try {
            f.setLine(Integer.parseInt(line));
        } catch (NumberFormatException ignored) {
        }
        return f;
    }

    private DebugStateDTO.Frame parseHitNode(DebugSession session, String out) {
        Matcher m = HIT_NODE.matcher(out);
        String file = null, line = null;
        while (m.find()) {
            file = m.group(1);
            line = m.group(2);
        }
        if (file == null) {
            return null;
        }
        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
        f.setFileName(relOrAbs(session, file));
        f.setMethodName("");
        try {
            f.setLine(Integer.parseInt(line));
        } catch (NumberFormatException ignored) {
        }
        return f;
    }

    private DebugStateDTO.Frame parseHitGo(DebugSession session, String out) {
        Matcher m = HIT_GO.matcher(out);
        String method = null, file = null, line = null;
        while (m.find()) {
            method = m.group(1);
            file = m.group(2);
            line = m.group(3);
        }
        if (file == null) {
            return null;
        }
        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
        f.setFileName(relOrAbs(session, file));
        f.setClassName(relOrAbs(session, file));
        f.setMethodName(method);
        try {
            f.setLine(Integer.parseInt(line));
        } catch (NumberFormatException ignored) {
        }
        return f;
    }

    private DebugStateDTO.Frame parseHitGdb(DebugSession session, String out) {
        Matcher m = HIT_GDB.matcher(out);
        String file = null, line = null;
        while (m.find()) {
            file = m.group(1);
            line = m.group(2);
        }
        if (file == null) {
            return null;
        }
        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
        f.setFileName(relOrAbs(session, file));
        f.setClassName(relOrAbs(session, file));
        f.setMethodName("");
        try {
            f.setLine(Integer.parseInt(line));
        } catch (NumberFormatException ignored) {
        }
        return f;
    }

    private List<DebugStateDTO.Frame> parseFrames(DebugSession session, String stackOut) {
        List<DebugStateDTO.Frame> frames = new ArrayList<>();
        switch (session.kind) {
            case "python" -> {
                for (String line : stackOut.split("\n")) {
                    Matcher m = FRAME_PY.matcher(line);
                    if (m.matches() && frames.size() < 50) {
                        DebugStateDTO.Frame f = new DebugStateDTO.Frame();
                        f.setFileName(relOrAbs(session, m.group(1)));
                        f.setClassName(moduleLabel(session, m.group(1)));
                        f.setMethodName(m.group(3).isEmpty() ? "<module>" : m.group(3));
                        try {
                            f.setLine(Integer.parseInt(m.group(2)));
                        } catch (NumberFormatException ignored) {
                        }
                        frames.add(f);
                    }
                }
            }
            case "node" -> {
                Matcher m = FRAME_NODE.matcher(stackOut);
                while (m.find() && frames.size() < 50) {
                    DebugStateDTO.Frame f = new DebugStateDTO.Frame();
                    f.setMethodName(m.group(1));
                    f.setFileName(relOrAbs(session, m.group(2)));
                    try {
                        f.setLine(Integer.parseInt(m.group(3)));
                    } catch (NumberFormatException ignored) {
                    }
                    frames.add(f);
                }
            }
            case "go" -> {
                Matcher m = FRAME_GO.matcher(stackOut);
                while (m.find() && frames.size() < 50) {
                    DebugStateDTO.Frame f = new DebugStateDTO.Frame();
                    f.setMethodName(m.group(1));
                    f.setFileName(relOrAbs(session, m.group(2)));
                    f.setClassName(relOrAbs(session, m.group(2)));
                    try {
                        f.setLine(Integer.parseInt(m.group(3)));
                    } catch (NumberFormatException ignored) {
                    }
                    frames.add(f);
                }
            }
            case "c" -> {
                Matcher m = FRAME_GDB.matcher(stackOut);
                while (m.find() && frames.size() < 50) {
                    DebugStateDTO.Frame f = new DebugStateDTO.Frame();
                    f.setMethodName(m.group(1));
                    f.setFileName(relOrAbs(session, m.group(2)));
                    f.setClassName(relOrAbs(session, m.group(2)));
                    try {
                        f.setLine(Integer.parseInt(m.group(3)));
                    } catch (NumberFormatException ignored) {
                    }
                    frames.add(f);
                }
            }
            default -> {
                Matcher m = FRAME_JAVA.matcher(stackOut);
                while (m.find() && frames.size() < 50) {
                    DebugStateDTO.Frame f = new DebugStateDTO.Frame();
                    f.setClassName(m.group(1));
                    f.setMethodName(m.group(2));
                    f.setFileName(m.group(3));
                    try {
                        f.setLine(m.group(4) != null ? Integer.parseInt(m.group(4)) : null);
                    } catch (NumberFormatException ignored) {
                    }
                    frames.add(f);
                }
            }
        }
        return frames;
    }

    private List<DebugStateDTO.Local> parseLocalsJava(String locals) {
        List<DebugStateDTO.Local> list = new ArrayList<>();
        for (String line : locals.split("\n")) {
            if (line.contains("Local variables") || line.contains("Internal exception")) {
                continue;
            }
            Matcher m = LOCAL_JAVA.matcher(line);
            if (m.matches() && list.size() < 100) {
                DebugStateDTO.Local l = new DebugStateDTO.Local();
                l.setName(m.group(1));
                l.setValue(truncate(m.group(2).trim(), 200));
                list.add(l);
            }
        }
        return list;
    }

    /** pdb p locals() 字典朴素解析（值含逗号的字符串会被截断——P3a 已知取舍） */
    private List<DebugStateDTO.Local> parseLocalsPython(String repr) {
        List<DebugStateDTO.Local> list = new ArrayList<>();
        if (repr.isBlank()) {
            return list;
        }
        Matcher m = LOCAL_PY.matcher(repr);
        while (m.find() && list.size() < 50) {
            DebugStateDTO.Local l = new DebugStateDTO.Local();
            l.setName(m.group(1));
            l.setValue(truncate(m.group(2).trim().replaceAll("'$", ""), 120));
            list.add(l);
        }
        return list;
    }

    /** dlv locals 行解析："x = 5"（嵌套结构体值为整体文本） */
    private List<DebugStateDTO.Local> parseLocalsGo(String out) {
        List<DebugStateDTO.Local> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            Matcher m = LOCAL_GO.matcher(line);
            if (m.matches() && list.size() < 50) {
                DebugStateDTO.Local l = new DebugStateDTO.Local();
                l.setName(m.group(1));
                l.setValue(truncate(m.group(2).trim(), 120));
                list.add(l);
            }
        }
        return list;
    }

    /** gdb info locals 行解析："x = 5"（tab 缩进，嵌套结构缩进更深按整体文本） */
    private List<DebugStateDTO.Local> parseLocalsGdb(String out) {
        List<DebugStateDTO.Local> list = new ArrayList<>();
        if (out.contains("No locals") || out.contains("No symbol")) {
            return list;
        }
        for (String line : out.split("\n")) {
            Matcher m = LOCAL_GDB.matcher(line);
            if (m.matches() && list.size() < 50) {
                DebugStateDTO.Local l = new DebugStateDTO.Local();
                l.setName(m.group(1));
                l.setValue(truncate(m.group(2).trim(), 120));
                list.add(l);
            }
        }
        return list;
    }

    // ==================== 命令与工具 ====================

    /** 发送调试命令并等待提示符（超时返回空串 = VM 运行中） */
    private String send(DebugSession session, String cmd, int waitSec) {
        String out = dbg(session.sandboxId, session.kind, "send " + q(cmd) + " " + waitSec);
        // 更新偏移（send 返回 chunk 后日志可能又略有增长，按实际返回内容推进）
        session.jdbOffset += out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return stripPrompt(session.kind, out);
    }

    /** 去掉尾部调试器提示符（java 兼容 main[1] 类上下文形态） */
    private static String stripPrompt(String kind, String out) {
        String t = out;
        if ("java".equals(kind)) {
            t = t.replaceFirst("(?s)\\n?[A-Za-z0-9_$]+\\[[0-9]+\\] $", "");
        }
        String prompt = promptOf(kind);
        if (t.endsWith(prompt)) {
            t = t.substring(0, t.length() - prompt.length());
        }
        return t.endsWith("\n") ? t.substring(0, t.length() - 1) : t;
    }

    /** 驱动脚本调用（带语言提示符） */
    private String dbg(String sandboxId, String kind, String subAndArgs) {
        return sh(sandboxId, "DPROMPT=" + q(promptOf(kind)) + " sh " + q(DBG_DIR + "/dbg.sh") + " " + subAndArgs, 30);
    }

    /** 临时断点定位键（与 applyHit 的帧键对齐） */
    private String tempKeyOf(DebugSession session, String path, int line) {
        switch (session.kind) {
            case "python": return absOf(session, path) + ":" + line;
            case "node": return repoRelOf(session, path) + ":" + line;
            case "go": return absOf(session, path) + ":" + line;
            case "c": return absOf(session, path) + ":" + line;
            default: return classOf(path) + ":" + line;
        }
    }

    private String tempKeyOfFrame(DebugSession session, DebugStateDTO.Frame f) {
        switch (session.kind) {
            case "python": return (f.getFileName() != null && f.getFileName().startsWith("/")
                    ? f.getFileName() : absOf(session, f.getFileName())) + ":" + f.getLine();
            case "node": return f.getFileName() + ":" + f.getLine();
            case "go", "c": return (f.getFileName() != null && f.getFileName().startsWith("/")
                    ? f.getFileName() : absOf(session, f.getFileName())) + ":" + f.getLine();
            default: return f.getClassName() + ":" + f.getLine();
        }
    }

    private String tempClearCmd(DebugSession session, String tempKey) {
        int idx = tempKey.lastIndexOf(':');
        String loc = tempKey.substring(0, idx);
        int line = Integer.parseInt(tempKey.substring(idx + 1));
        switch (session.kind) {
            case "python": return "clear " + q(loc) + ":" + line;
            case "node": return "cb(" + q(loc) + ", " + line + ")";
            case "go": return "clearall " + q(loc) + ":" + line;
            case "c": return "clear " + q(loc) + ":" + line;
            default: return "clear " + loc + ":" + line;
        }
    }

    /** 源文件路径 → 全限定类名（Maven src/main/java、src/test/java 约定优先；仅 java 驱动） */
    static String classOf(String path) {
        String p = DevWorkspaceService.sanitizeWorkspaceRel(path);
        if (!p.endsWith(".java")) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "断点仅支持 Java 源文件: " + path);
        }
        String rel = p;
        for (String root : new String[]{"src/main/java/", "src/test/java/"}) {
            int i = p.indexOf(root);
            if (i >= 0) {
                rel = p.substring(i + root.length());
                break;
            }
        }
        String cls = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
        if (!CLASS_NAME.matcher(cls).matches()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "无法从路径推断 Java 类名: " + path);
        }
        return cls;
    }

    /** 工作空间相对路径 → 容器绝对路径（限定仓库目录内） */
    private String absOf(DebugSession session, String repoRel) {
        return session.repoDir + "/" + DevWorkspaceService.sanitizeWorkspaceRel(repoRel);
    }

    /** 工作空间相对路径 → 仓库相对路径（剥掉仓库前缀） */
    private String repoRelOf(DebugSession session, String path) {
        String p = DevWorkspaceService.sanitizeWorkspaceRel(path);
        String prefix = p.startsWith("projects/") ? p.substring(p.indexOf('/', 9) + 1) : p;
        return prefix;
    }

    /** 容器绝对路径 → 仓库相对路径（仓库外路径原样保留，前端定位时自然失败提示） */
    private String relOrAbs(DebugSession session, String abs) {
        if (abs.startsWith(session.repoDir + "/")) {
            abs = abs.substring(session.repoDir.length() + 1);
        }
        return abs.startsWith("./") ? abs.substring(2) : abs;
    }

    /** python 文件路径 → 模块点路径标签 */
    private String moduleLabel(DebugSession session, String abs) {
        String rel = relOrAbs(session, abs);
        return rel.endsWith(".py") ? rel.substring(0, rel.length() - 3).replace('/', '.') : rel;
    }

    private DebugSession sessionOf(String projectId) {
        DebugSession session = sessions.get(requireUserId());
        if (session == null || !session.projectId.equals(projectId)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "调试会话未启动，请先「调试运行」");
        }
        return session;
    }

    private static String requireUserId() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** exec 短命令：输出即语义（脚本内已把错误折叠为可读文本） */
    private String sh(String sandboxId, String cmd, int timeoutSec) {
        ExecCommandResponse r = sandboxClient.execCommand(sandboxId, cmd, timeoutSec);
        return r.getStdout() != null ? r.getStdout() : "";
    }

    private static String q(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
