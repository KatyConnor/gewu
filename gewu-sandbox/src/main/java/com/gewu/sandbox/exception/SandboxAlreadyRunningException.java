package com.gewu.sandbox.exception;

import com.gewu.common.result.ResultCode;

/**
 * 沙箱已在运行异常.
 */
public class SandboxAlreadyRunningException extends SandboxException {

    public SandboxAlreadyRunningException(String sandboxId) {
        super(ResultCode.SANDBOX_RUNTIME_ERROR, "沙箱已在运行: " + sandboxId);
    }
}
