package com.gewu.agent.engine.entry;

/**
 * 子目录嵌套入口发现（设计文档 57，方案 B：工具访问感知/惰性就近）.
 *
 * <p>语义对齐 agents.md 规范"就近优先"：智能体触碰某文件时，自该文件所在目录
 * 向上逐层探测入口文件至会话根（**不含根**——根文件已随 system prompt 注入），
 * 每层按 GEWU.md → AGENTS.md 顺序，命中即停。
 *
 * <p>纯函数：probe 回调由运行时提供（平台=沙箱读取，桌面=本地 FS），
 * 返回 null 视为该文件不存在；探测异常由调用方在回调内消化。
 */
public final class NestedEntryFinder {

    public static final String NAME_GEWO = "GEWU.md";
    public static final String NAME_AGENTS = "AGENTS.md";

    /** 命中结果：relativeDir 为相对会话根的目录（如 packages/foo）。 */
    public record NestedHit(String relativeDir, String fileName, String content) {}

    /** 探测回调：读取 dir 下的入口文件，返回内容；null/空 = 不存在。 */
    public interface Probe {
        String read(String dir, String fileName);
    }

    private NestedEntryFinder() {
    }

    /**
     * 就近发现入口文件.
     *
     * @param root       会话根（相对路径基准，"" 表示根；该层不参与探测）
     * @param targetPath 被触碰文件的相对路径（相对会话根）
     * @param probe      探测回调
     * @return 命中结果；目标在根层、路径越界或无命中返回 null
     */
    public static NestedHit find(String root, String targetPath, Probe probe) {
        if (targetPath == null || targetPath.isBlank() || probe == null) {
            return null;
        }
        String rootNorm = normalizePath(root);
        String target = normalizePath(targetPath);
        if (target == null) {
            return null; // 绝对路径或含 .. → 越界拒绝
        }
        String dir = parentDir(target);
        while (dir != null && !dir.isBlank() && !dir.equals(rootNorm)) {
            for (String name : new String[]{NAME_GEWO, NAME_AGENTS}) {
                String content;
                try {
                    content = probe.read(dir, name);
                } catch (Exception e) {
                    content = null;
                }
                if (content != null && !content.isEmpty()) {
                    return new NestedHit(dir, name, content);
                }
            }
            dir = parentDir(dir);
        }
        return null;
    }

    /**
     * 相对路径规范化（公开供接线层探测/去重前统一口径，RPT-005 F-504）：
     * 剥 ./ 前缀；绝对路径或含 .. 段返回 null（越界拒绝）。
     * 注意：显式 root≠"" 时，targetPath 必须以 root 前缀开头，否则探测范围会静默漂移——调用方自行保证。
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/") || p.equals("/") || p.isBlank()) {
            return p.isBlank() ? "" : null;
        }
        for (String seg : p.split("/")) {
            if ("..".equals(seg)) {
                return null;
            }
        }
        return p.replaceAll("/+", "/");
    }

    /** 取目录部分：无斜杠返回 ""（根层）；根层的 parent 由调用方空串判定终止。 */
    private static String parentDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? "" : path.substring(0, idx);
    }
}
