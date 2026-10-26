package com.gewu.interfaceapi.terminal;

import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import com.gewu.common.result.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 网页终端 WS 反向代理（设计文档 44 §2）：浏览器 ⇄ 本端点 ⇄ gewu-sandbox PTY 端点。
 * <p>浏览器侧鉴权 = 一次性 ticket（{@link TerminalTicketService} 签发时已绑定
 * userId 与已就绪的 dev 沙箱）；interface → sandbox 侧 = 既有内部密钥握手。
 * 终端数据为二进制帧直通（零解析），控制帧 JSON 文本透传。
 */
@Slf4j
@Component
public class TerminalProxyWsHandler extends AbstractWebSocketHandler {

    private final TerminalTicketService ticketService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${gewu.sandbox.api.url:http://localhost:8082}")
    private String sandboxBaseUrl;

    @Value("${gewu.sandbox.api.internal-key:}")
    private String internalApiKey;

    public TerminalProxyWsHandler(TerminalTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession browserWs) {
        try {
            String ticket = param(browserWs, "ticket");
            int cols = intParam(browserWs, "cols", 120);
            int rows = intParam(browserWs, "rows", 32);
            TerminalTicketService.Payload payload = ticketService.consume(ticket);

            ConcurrentWebSocketSessionDecorator safeBrowser =
                    new ConcurrentWebSocketSessionDecorator(browserWs, 10_000, 1024 * 1024);
            // sandboxBaseUrl 已含 /api/v1/sandboxes 前缀（与 SandboxClient 同源配置），此处仅接短路径
            String target = wsUrl(sandboxBaseUrl) + "/ws/pty"
                    + "?sandboxId=" + payload.sandboxId() + "&userId=" + payload.userId()
                    + "&cols=" + cols + "&rows=" + rows;

            SandboxPipe pipe = new SandboxPipe(safeBrowser);
            browserWs.getAttributes().put("pipe", pipe);
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            if (internalApiKey != null && !internalApiKey.isBlank()) {
                builder.header("X-Internal-Api-Key", internalApiKey);
            }
            WebSocket sandboxWs = builder.buildAsync(URI.create(target), pipe).join();
            pipe.bind(sandboxWs);
            log.info("终端代理建立: user={}, sandbox={}", payload.userId(), payload.sandboxId());
        } catch (IllegalArgumentException e) {
            closeQuietly(browserWs, CloseStatus.NOT_ACCEPTABLE.withReason(e.getMessage()));
        } catch (Exception e) {
            // 解包握手异常：给出上游响应码与响应体，避免只见异常类名无法定位
            Throwable cause = e;
            while (cause != null && !(cause instanceof java.net.http.WebSocketHandshakeException)) {
                cause = cause.getCause();
            }
            if (cause instanceof java.net.http.WebSocketHandshakeException hse
                    && hse.getResponse() != null) {
                log.warn("终端代理建立失败: 上游握手响应码={}, body={}",
                        hse.getResponse().statusCode(), hse.getResponse().body());
            } else {
                log.warn("终端代理建立失败: {}", e.toString(), e);
            }
            closeQuietly(browserWs, CloseStatus.SERVER_ERROR.withReason("terminal proxy failed"));
        }
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession browserWs, TextMessage message) {
        getPipe(browserWs).ifForwardText(message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(org.springframework.web.socket.WebSocketSession browserWs, BinaryMessage message) {
        java.nio.ByteBuffer buf = message.getPayload();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        getPipe(browserWs).ifForwardBinary(bytes);
    }

    @Override
    public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession browserWs, CloseStatus status) {
        SandboxPipe pipe = (SandboxPipe) browserWs.getAttributes().get("pipe");
        if (pipe != null) {
            pipe.closeSandbox();
        }
    }

    // ==================== 内部：双向管道 ====================

    private final class SandboxPipe implements WebSocket.Listener {
        private final org.springframework.web.socket.WebSocketSession browser;
        private volatile WebSocket sandboxWs;
        /** sandbox 就绪前到达的浏览器消息缓冲（attach 为异步，防止首键丢失） */
        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private volatile boolean sandboxReady = false;

        SandboxPipe(org.springframework.web.socket.WebSocketSession browser) {
            this.browser = new ConcurrentWebSocketSessionDecorator(browser, 10_000, 1024 * 1024);
        }

        void bind(WebSocket ws) {
            this.sandboxWs = ws;
            ws.request(1);
            // 就绪确认由 sandbox 的 ready 控制帧触发；此处不提前放行
        }

        void onSandboxReady() {
            sandboxReady = true;
            Runnable r;
            while ((r = pending.poll()) != null) {
                r.run();
            }
        }

        void ifForwardText(String payload) {
            forward(() -> sandboxWs.sendText(payload, true));
        }

        void ifForwardBinary(byte[] bytes) {
            forward(() -> sandboxWs.sendBinary(ByteBuffer.wrap(bytes), true));
        }

        private void forward(Runnable send) {
            if (!sandboxReady) {
                pending.add(send);
                return;
            }
            send.run();
        }

        void closeSandbox() {
            WebSocket ws = sandboxWs;
            if (ws != null) {
                try {
                    ws.sendClose(CloseStatus.NORMAL.getCode(), "bye");
                } catch (Exception ignore) {
                    // 对端可能已断开
                }
            }
        }

        // ---- WebSocket.Listener（sandbox → 浏览器方向） ----

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            String text = data.toString();
            if (text.contains("\"ready\"")) {
                onSandboxReady();
            }
            try {
                browser.sendMessage(new TextMessage(last ? data.toString() : data.toString()));
            } catch (Exception e) {
                log.debug("转发到浏览器失败: {}", e.getMessage());
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            try {
                browser.sendMessage(new BinaryMessage(bytes));
            } catch (Exception e) {
                log.debug("终端输出转发失败: {}", e.getMessage());
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            try {
                browser.close(CloseStatus.NORMAL);
            } catch (Exception ignore) {
                // 浏览器侧可能已断开
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("终端上游异常: {}", error.getMessage());
            try {
                browser.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignore) {
                // 浏览器侧可能已断开
            }
        }
    }

    private SandboxPipe getPipe(org.springframework.web.socket.WebSocketSession ws) {
        SandboxPipe pipe = (SandboxPipe) ws.getAttributes().get("pipe");
        if (pipe == null) {
            throw BusinessException.of(ResultCode.SYSTEM_ERROR, "终端会话未就绪");
        }
        return pipe;
    }

    private static String wsUrl(String httpUrl) {
        return httpUrl.trim().replaceFirst("^http", "ws");
    }

    private static String param(org.springframework.web.socket.WebSocketSession ws, String name) {
        URI uri = ws.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private static int intParam(org.springframework.web.socket.WebSocketSession ws, String name, int def) {
        String v = param(ws, name);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void closeQuietly(org.springframework.web.socket.WebSocketSession ws, CloseStatus status) {
        try {
            ws.close(status);
        } catch (Exception ignore) {
            // 对端可能已断开
        }
    }
}
