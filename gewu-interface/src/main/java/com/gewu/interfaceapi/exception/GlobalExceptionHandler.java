package com.gewu.interfaceapi.exception;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * 全局异常处理器 — 统一异常响应格式。
 * <p>SSE 流式端点（Accept: text/event-stream）的异常必须返回 event-stream
 * 错误帧而非 JSON：内容协商对 JSON 会失败产生 406（用户实报两类：异步超时、
 * 编排空定义 IAE）。所有处理器统一经 {@link #respond} 分流。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Object handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return respond(request, HttpStatus.OK,
                Result.fail(e.getCode(), e.getMessage()), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败: {}", message);
        return respond(request, HttpStatus.OK,
                Result.fail(ResultCode.PARAM_INVALID, message), message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("参数异常: {}", e.getMessage());
        return respond(request, HttpStatus.OK,
                Result.fail(ResultCode.PARAM_INVALID, e.getMessage()), e.getMessage());
    }

    /**
     * 异步请求超时（SSE 流式端点）。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Object handleAsyncRequestTimeout(AsyncRequestTimeoutException e, HttpServletRequest request) {
        log.warn("异步请求超时: uri={}, accept={}", request.getRequestURI(), request.getHeader("Accept"));
        String message = "生成超时：服务端生成超过时间上限，连接已断开。已生成的部分内容已保存，可重新进入会话查看后重试。";
        return respond(request, HttpStatus.OK, null, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // 非 SSE 分支维持 500（SSE 分支返回 ResponseEntity 自带状态）
    public Object handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常", e);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR,
                Result.fail(ResultCode.SYSTEM_ERROR, e.getMessage()), "AI 处理失败: " + e.getMessage());
    }

    // ==================== SSE / JSON 分流 ====================

    /**
     * 按请求 Accept 分流：SSE 请求返回 event-stream 错误帧（前端现有
     * error 事件路径展示），普通请求返回 JSON Result 信封。
     */
    private Object respond(HttpServletRequest request, HttpStatus status,
                           Result<?> jsonBody, String message) {
        if (isSseRequest(request)) {
            return sseErrorFrame(message);
        }
        if (jsonBody == null) {
            // 无 JSON 信封可用（如异步超时）时的兜底 JSON
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":13005,\"message\":" + jsonString(message) + ",\"data\":null,\"success\":false}");
        }
        return jsonBody;
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /** SSE 错误帧：HTTP 200 + event-stream，body 为标准 data 行 */
    private ResponseEntity<String> sseErrorFrame(String message) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("data: {\"type\":\"error\",\"errorMessage\":" + jsonString(message) + "}\n\n");
    }

    /** 消息转 JSON 字符串字面量（引号/反斜杠/控制符），避免手拼 JSON 注入 */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
