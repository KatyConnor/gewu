package com.gewu.sandbox.exception;

import com.gewu.common.result.ResultCode;

/**
 * 沙箱命令执行超时异常.
 */
public class SandboxCommandTimeoutException extends SandboxException {

    public SandboxCommandTimeoutException(int timeoutSeconds) {
        super(ResultCode.SANDBOX_TIMEOUT, "命令执行超时: " + timeoutSeconds + "秒");
    }
}
