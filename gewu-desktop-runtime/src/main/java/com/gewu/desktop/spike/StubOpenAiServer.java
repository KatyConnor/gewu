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

    private final java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
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
                if (phase.getAndIncrement() == 0) {
                    // 第一轮：驱动内置 write_file 工具（视觉回归：时间线工具行 + 授权 + 文件落地）
                    String toolArgs = "{\\\"path\\\":\\\"visual-sort.txt\\\",\\\"content\\\":\\\"冒泡排序验证（视觉回归）\\n第二行\\\"}";
                    chunk(out, "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Spike：需要创建文件，调用 write_file。\"}}]}");
                    chunk(out, "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":\"" + toolArgs + "\"}}]}}]}");
                    chunk(out, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
                } else {
                    // 第二轮：工具结果回灌后的最终正文
                    chunk(out, "{\"choices\":[{\"delta\":{\"content\":\"visual-sort.txt 已创建，内容已写入。\"}}]}");
                    chunk(out, "{\"choices\":[{\"delta\":{\"content\":\"冒泡排序验证完成（视觉回归）。\"}}]}");
                    chunk(out, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30,\"total_tokens\":50}}");
                }
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
