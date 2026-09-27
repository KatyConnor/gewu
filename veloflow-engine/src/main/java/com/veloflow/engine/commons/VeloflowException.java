package com.veloflow.engine.commons;

import lombok.Getter;

/** Veloflow 业务异常（错误码独立段 41001+，与宿主错误码隔离） */
@Getter
public class VeloflowException extends RuntimeException {

    private final int code;

    public VeloflowException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static VeloflowException of(int code) {
        return new VeloflowException(code, "veloflow error " + code);
    }

    public static VeloflowException of(int code, String message) {
        return new VeloflowException(code, message);
    }
}
