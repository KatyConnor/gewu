package com.gewu.sandbox.exception;

import com.gewu.common.result.ResultCode;

/**
 * 沙箱不存在异常.
 */
public class SandboxNotFoundException extends SandboxException {

    public SandboxNotFoundException(String sandboxId) {
        super(ResultCode.SANDBOX_NOT_FOUND, "沙箱不存在: " + sandboxId);
    }
}
