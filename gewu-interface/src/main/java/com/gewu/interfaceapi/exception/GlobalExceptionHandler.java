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
 * 全局异常处理器 — 统一异常响应格式.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.PARAM_INVALID, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_INVALID, e.getMessage());
    }

    /**
     * 异步请求超时（SSE 流式端点）专用处理（用户实报 406 修复）。
     * <p>SSE 请求的 Accept 仅接受 text/event-stream，落入通用 JSON 处理器会因
     * 内容协商失败抛 HttpMediaTypeNotAcceptableException → 前端收到不可用的 406。
     * 此处对 SSE 请求返回 text/event-stream 错误帧，前端现有 error 事件路径即可展示。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleAsyncRequestTimeout(AsyncRequestTimeoutException e,
                                                            HttpServletRequest request) {
        log.warn("异步请求超时: uri={}, accept={}", request.getRequestURI(), request.getHeader("Accept"));
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            String frame = "data: {\"type\":\"error\",\"errorMessage\":"
                    + "\"生成超时：服务端生成超过时间上限，连接已断开。已生成的部分内容已保存，可重新进入会话查看后重试。\"}\n\n";
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(frame);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":13005,\"message\":\"请求处理超时，请稍后重试\",\"data\":null,\"success\":false}");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR, e.getMessage());
    }
}