package com.gewu.common.enums;

/**
 * 沙箱运行时类型枚举.
 * <p>DEC-AE-PLAN-2026-11-03（AE-P2-6 · 2026-11-13）：当前<b>唯一实现</b>为 {@link #DOCKER}
 * （gewu-sandbox DockerSandboxProvider）；FIRECRACKER/GVISOR/ISULAD/KATA 为预留枚举位（无 Java 实现，
 * 根目录 firecracker tgz 为待用二进制）——microVM 方向需立项级决策（隔离诉求出现前 Docker 满足当前
 * 威胁模型：network=none + 只读根文件系统 + 资源限额），显式标注避免伪多态误导。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-07-09
 */
public enum SandboxRuntime {

    /** 未实现（预留位，DEC-AE-PLAN-2026-11-03） */
    FIRECRACKER("firecracker", "Firecracker MicroVM"),
    /** 未实现（预留位） */
    GVISOR("gvisor", "gVisor runsc"),
    /** 未实现（预留位） */
    ISULAD("isulad", "iSulad 容器"),
    /** 唯一已实现运行时（DockerSandboxProvider） */
    DOCKER("docker", "Docker runc"),
    /** 未实现（预留位） */
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