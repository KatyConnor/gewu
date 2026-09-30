package com.gewu.desktop.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径沙箱（ADR-009 自加固三件套之一）：
 * 内置文件工具绕过引擎 ToolExecutor 五段管线，本类是桌面端文件安全的最后防线。
 * <ul>
 *   <li>仅允许工作区内相对路径（拒绝绝对路径 / ~ / 反斜杠）</li>
 *   <li>拒绝 {@code ..} 段（强于平台 sanitizeRelative 的逐段校验）</li>
 *   <li>realpath 二次校验：对已存在的最深祖先解析真实路径，防符号链接逃逸</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class PathGuard {

    private PathGuard() {
    }

    /** 解析并校验：返回工作区内的绝对目标路径（文件可不存在——新建场景） */
    public static Path secureResolve(Path workspaceRoot, String relativePath) throws IOException {
        Path realRoot = workspaceRoot.toRealPath();
        String p = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        if (p.isEmpty() || p.startsWith("/") || p.startsWith("~") || p.contains(":")) {
            throw new SecurityException("路径必须为工作区内的相对路径: " + relativePath);
        }
        if (p.equals(".") || p.startsWith("../") || p.equals("..") || p.contains("/../") || p.endsWith("/..")) {
            throw new SecurityException("路径不允许包含 ..: " + relativePath);
        }
        Path target = realRoot.resolve(p).normalize();
        if (!target.startsWith(realRoot)) {
            throw new SecurityException("路径越界: " + relativePath);
        }
        // 符号链接逃逸检测：沿已存在的最深祖先做 realpath 前缀校验
        Path cur = target;
        while (cur != null && !Files.exists(cur)) {
            cur = cur.getParent();
        }
        if (cur != null) {
            Path realCur = cur.toRealPath();
            if (!realCur.startsWith(realRoot)) {
                throw new SecurityException("符号链接逃逸: " + relativePath);
            }
        }
        return target;
    }
}
