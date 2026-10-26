package com.gewu.admin.exception;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.Result;
import com.gewu.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理服务全局异常处理器 — 与主应用 GlobalExceptionHandler 同契约（无 SSE 分支）。
 * <p>缺失本处理器时业务拒绝（重复编码/删除护栏等 BusinessException）会穿透到
 * /error 并被认证入口点改写为 401，前端 401 拦截随即清凭证踢回登录页
 * （验证应用系统功能时实证，机构管理等既有功能同样受累）。
 */
@Slf4j
@RestControllerAdvice
public class AdminGlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.PARAM_INVALID, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR, e.getMessage());
    }
}
