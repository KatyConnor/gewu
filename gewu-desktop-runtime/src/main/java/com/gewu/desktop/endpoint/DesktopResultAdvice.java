package com.gewu.desktop.endpoint;

import com.gewu.common.result.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Result 信封统一包装（D1-13 契约测试首日抓获的违约）：
 * 平台契约所有响应为 {code, message, data, timestamp}——桌面 Profile 端点此前返回裸 DTO，
 * 共享 api-client（postChat/fetchModels 按信封解包）与渲染层数据面将全部失配。
 * <p>豁免：SSE（SseEmitter/StreamingResponseBody——流式帧自带契约）、字符串、已包装体。
 *
 * @since 1.0.0
 */
@RestControllerAdvice(basePackages = "com.gewu.desktop.endpoint")
public class DesktopResultAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        Class<?> type = returnType.getParameterType();
        return !SseEmitter.class.isAssignableFrom(type)
                && !org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class.isAssignableFrom(type)
                && type != String.class
                && type != byte[].class;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType, Class selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof Result) {
            return body;
        }
        return Result.success(body);
    }
}
