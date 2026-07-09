package com.gewu.sandbox.exception;

import com.gewu.common.result.ResultCode;

/**
 * 沙箱资源超限异常.
 */
public class SandboxResourceLimitExceededException extends SandboxException {

    public SandboxResourceLimitExceededException(String message) {
        super(ResultCode.SANDBOX_RESOURCE_LIMIT, message);
    }
}
