package com.veloflow.engine.commons;

/** Veloflow 错误码段（41001+，独立于宿主错误码空间） */
public final class VeloflowErrorCode {

    public static final int FLOW_NOT_FOUND = 41001;
    public static final int FLOW_NODE_NOT_FOUND = 41002;
    public static final int FLOW_INSTANCE_NOT_FOUND = 41003;
    public static final int FLOW_INVALID_STATE = 41004;
    public static final int FLOW_VALIDATION_FAILED = 41005;
    public static final int FLOW_UNAUTHORIZED = 41006;

    private VeloflowErrorCode() {
    }
}
