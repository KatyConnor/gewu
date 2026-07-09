package com.gewu.interfaceapi.controller;

import com.gewu.application.sse.SseEventManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 接口 — 会话维度的实时事件推送.
 */
@RestController
@RequestMapping("/api/v1/sse")
@RequiredArgsConstructor
@Tag(name = "SSE 推送", description = "会话实时事件订阅")
public class SseController {

    private final SseEventManager sseEventManager;

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "订阅会话事件", description = "建立 SSE 长连接以接收会话内实时消息推送")
    public SseEmitter subscribe(@PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        sseEventManager.addEmitter(sessionId, emitter);
        return emitter;
    }
}