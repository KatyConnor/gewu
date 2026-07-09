package com.gewu.common.enums;

/**
 * 沙箱运行时类型枚举.
 */
public enum SandboxRuntime {

    FIRECRACKER("firecracker", "Firecracker MicroVM"),
    GVISOR("gvisor", "gVisor runsc"),
    ISULAD("isulad", "iSulad 容器"),
    DOCKER("docker", "Docker runc"),
    KATA("kata", "Kata Containers");

    private final String code;
    private final String description;

    SandboxRuntime(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SandboxRuntime fromCode(String code) {
        for (SandboxRuntime runtime : values()) {
            if (runtime.code.equals(code)) return runtime;
        }
        throw new IllegalArgumentException("未知沙箱运行时: " + code);
    }
}