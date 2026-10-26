package com.gewu.sandbox.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 网页终端 WS 端点（/api/v1/sandboxes/ws/pty）。
 * <p>握手安全由既有 InternalApiKeyFilter 承担（interface 反向代理在握手请求
 * 携带 X-Internal-Api-Key），查询参数 sandboxId/userId 由 interface 侧
 * 校验用户身份与 ticket 后回填——本端点不直接面向浏览器。
 * <p>协议：控制帧 JSON 文本（ready/exit/error/closed/pong），终端数据为二进制帧。
 */
@Slf4j
@Component
public class TerminalWsHandler extends AbstractWebSocketHandler {

    private final TerminalPtyService ptyService;
    private final ObjectMapper objectMapper;

    public TerminalWsHandler(TerminalPtyService ptyService, ObjectMapper objectMapper) {
        this.ptyService = ptyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession ws) {
        try {
            String sandboxId = param(ws, "sandboxId");
            String userId = param(ws, "userId");
            int cols = intParam(ws, "cols", 120);
            int rows = intParam(ws, "rows", 32);
            if (sandboxId == null || sandboxId.isBlank() || userId == null || userId.isBlank()) {
                ws.close(CloseStatus.BAD_DATA.withReason("缺少 sandboxId/userId"));
                return;
            }
            ptyService.attach(sandboxId, userId, cols, rows, ws);
        } catch (Exception e) {
            log.warn("终端握手失败: {}", e.getMessage());
            try {
                ws.close(CloseStatus.SERVER_ERROR.withReason("attach failed"));
            } catch (Exception ignore) {
                // 连接可能已被对端关闭
            }
        }
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession ws, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            TerminalPtyService.PtySession session = ptyService.findByWs(ws);
            if ("ping".equals(type)) {
                if (session != null) {
                    ptyService.touch(session);
                }
                send(ws, "{\"type\":\"pong\"}");
            } else if ("resize".equals(type) && session != null) {
                ptyService.resize(session, node.path("cols").asInt(120), node.path("rows").asInt(32));
            }
        } catch (Exception e) {
            log.debug("终端控制帧处理失败: {}", e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(org.springframework.web.socket.WebSocketSession ws, BinaryMessage message) {
        TerminalPtyService.PtySession session = ptyService.findByWs(ws);
        if (session != null) {
            ptyService.writeInput(session, message.getPayload().array());
        }
    }

    @Override
    public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession ws, CloseStatus status) {
        TerminalPtyService.PtySession session = ptyService.findByWs(ws);
        if (session != null) {
            ptyService.close(session, "ws_closed");
        }
    }

    private void send(org.springframework.web.socket.WebSocketSession ws, String json) {
        try {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignore) {
            // 对端已断开
        }
    }

    private static String param(org.springframework.web.socket.WebSocketSession ws, String name) {
        if (ws.getUri() == null || ws.getUri().getQuery() == null) {
            return null;
        }
        for (String pair : ws.getUri().getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intParam(org.springframework.web.socket.WebSocketSession ws, String name, int def) {
        String v = param(ws, name);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
