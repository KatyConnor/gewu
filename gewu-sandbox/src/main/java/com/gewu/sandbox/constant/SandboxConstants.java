package com.gewu.sandbox.constant;

import java.util.List;

public final class SandboxConstants {

    private SandboxConstants() {}

    public static final int DEFAULT_CPU_LIMIT = 2000;
    public static final int DEFAULT_MEMORY_LIMIT_MB = 4096;
    public static final int DEFAULT_DISK_LIMIT_MB = 10240;
    public static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    public static final int MAX_CPU_LIMIT = 8000;
    public static final int MAX_MEMORY_LIMIT_MB = 16384;
    public static final int MAX_DISK_LIMIT_MB = 51200;
    public static final int MAX_TIMEOUT_SECONDS = 86400;

    public static final List<String> ALLOWED_IMAGE_PREFIXES = List.of(
        "harbor.internal:5000/gewu/sandbox/",
        "gewu/sandbox-",
        "python:",
        "node:",
        "openjdk:",
        "alpine:",
        "ubuntu:"
    );

    public static final List<String> BLOCKED_COMMANDS = List.of(
        "rm -rf /",
        "rm -rf /*",
        "mkfs",
        "dd if=/dev/zero",
        "format",
        "shutdown",
        "reboot",
        "init 0",
        "init 6"
    );

    public static final String SECCOMP_PROFILE_DEFAULT = "docker-default";
    public static final String APPARMOR_PROFILE_DEFAULT = "docker-default";
}