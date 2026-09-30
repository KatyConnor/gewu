package com.gewu.desktop.spike;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Spike 用 OpenAI 兼容 stub 服务器：验证引擎 {@code OpenAiCompatibleClient} 的真实流式解析链路
 * （SSE 分帧 / reasoning_content 与 content 分离 / finish_reason / [DONE]），无需真实厂商 Key。
 * <p>Phase 1 由 DesktopLocalLlmProvider（真实厂商直连）替换，本类随之移除。
 *
 * @since 1.0.0
 */
public class StubOpenAiServer {

    private HttpServer server;
    private int port;

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                chunk(out, "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Spike：正在解析请求并组织回复。\"}}]}");
                chunk(out, "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}");
                chunk(out, "{\"choices\":[{\"delta\":{\"content\":\"，格物桌面运行时流式链路工作正常。\"}}]}");
                chunk(out, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":24,\"total_tokens\":36}}");
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.start();
        this.port = server.getAddress().getPort();
    }

    private void chunk(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
