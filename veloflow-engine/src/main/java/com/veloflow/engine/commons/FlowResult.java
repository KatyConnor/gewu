package com.veloflow.engine.commons;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Veloflow REST 响应包装 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowResult<T> {

    private int code;
    private String message;
    private T data;

    public static <T> FlowResult<T> success(T data) {
        return new FlowResult<>(10000, "success", data);
    }

    public static FlowResult<Void> success() {
        return new FlowResult<>(10000, "success", null);
    }

    public static <T> FlowResult<T> fail(int code, String message) {
        return new FlowResult<>(code, message, null);
    }
}
