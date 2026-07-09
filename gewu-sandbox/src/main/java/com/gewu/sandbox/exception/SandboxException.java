package com.gewu.sandbox.exception;

import com.gewu.common.result.ResultCode;
import lombok.Getter;

/**
 * 沙箱异常基类.
 */
@Getter
public class SandboxException extends RuntimeException {

    private final int code;

    public SandboxException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public SandboxException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public SandboxException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.code = resultCode.getCode();
    }
}
