package com.gewu.common.entry;

/**
 * 入口调度文件层级选择器 — 纯函数，平台端与桌面端共用（设计文档 56 §5.2）.
 *
 * <p>裁决规则（按序判定）：
 * <pre>
 * 0. 双方都不存在                          → none（no-entry）
 * 1. 仅一个存在                            → 选它（only-candidate）
 * 2. 两者都有：
 *    a. 双方都声明版本且不等               → 版本最高者胜（version-higher，格物 opt-in 协议）
 *    b. 任一侧无版本，或双方版本相等       → 项目级胜（project-default，主流就近语义兜底）
 * </pre>
 *
 * <p>设计约束（v1.2 评审质询修订）：默认语义与 agents.md 规范"nearest wins"一致，
 * 导入外部标准 AGENTS.md 零修改生效；版本裁决仅在双方都声明版本时生效，
 * 自有语义不改变标准文件的行为含义。
 */
public final class EntryFileSelector {

    /** 叠加语义（设计文档 56 §12.1）：SELECT=单选；MERGE=全局段+项目段拼接 */
    public enum Layering { SELECT, MERGE }

    public static final String REASON_NO_ENTRY = "no-entry";
    public static final String REASON_ONLY_CANDIDATE = "only-candidate";
    public static final String REASON_VERSION_HIGHER = "version-higher";
    public static final String REASON_PROJECT_DEFAULT = "project-default";
    public static final String REASON_MERGED = "merged";
    public static final String REASON_PROJECT_OVERRIDE = "project-override";

    /** 注释标记行：gewu-entry: 后必须紧跟版本数字（散文提及不误判，CR P2#7 收紧） */
    private static final java.util.regex.Pattern MARKER_VERSION =
            java.util.regex.Pattern.compile("gewu-entry\\s*[:：]\\s*(v?\\d+(?:\\.\\d+){0,2})");

    private EntryFileSelector() {
    }

    /**
     * 跨层裁决（select 语义，向后兼容入口）。
     */
    public static EntrySelection select(EntryCandidate global, EntryCandidate project) {
        return select(global, project, Layering.SELECT, false);
    }

    /**
     * 跨层裁决（支持叠加语义，设计文档 56 §12.1 P2）.
     *
     * @param layering        SELECT=单选（默认，主流就近语义）；MERGE=双侧拼接
     * @param projectOverride 项目文件 frontmatter 是否声明 override:true
     *                        （仅 MERGE 下有意义：声明则退回单选、项目级独占）
     */
    public static EntrySelection select(EntryCandidate global, EntryCandidate project,
                                        Layering layering, boolean projectOverride) {
        if (isEmpty(global) && isEmpty(project)) {
            return EntrySelection.builder()
                    .source("none").reason(REASON_NO_ENTRY).build();
        }
        if (isEmpty(global)) {
            return chosen(project, REASON_ONLY_CANDIDATE);
        }
        if (isEmpty(project)) {
            return chosen(global, REASON_ONLY_CANDIDATE);
        }

        boolean merge = layering == Layering.MERGE && !projectOverride;
        if (merge) {
            String merged = global.getContent() + "\n\n---\n\n" + project.getContent();
            return EntrySelection.builder()
                    .source(global.getPath() + " + " + project.getPath())
                    .path(global.getPath())
                    .version(project.getVersion())
                    .reason(REASON_MERGED)
                    .content(merged)
                    .build();
        }
        if (layering == Layering.MERGE) {
            // MERGE + override → 项目级独占（项目显式声明不受全局叠加影响）
            return chosen(project, REASON_PROJECT_OVERRIDE);
        }

        String gv = global.getVersion();
        String pv = project.getVersion();
        // 规则 2a：双方都声明版本且不等 → 版本最高者胜（opt-in 协议）
        if (gv != null && pv != null) {
            int cmp = compareVersions(gv, pv);
            if (cmp > 0) {
                return chosen(global, REASON_VERSION_HIGHER);
            }
            if (cmp < 0) {
                return chosen(project, REASON_VERSION_HIGHER);
            }
        }
        // 规则 2b：任一侧无版本，或双方版本相等 → 项目级胜（主流就近语义兜底）
        return chosen(project, REASON_PROJECT_DEFAULT);
    }

    /**
     * 解析 frontmatter 的 override 声明（仅认可 true）。
     * 解析失败或未声明返回 false。
     */
    public static boolean extractOverride(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String[] lines = content.split("\n", -1);
        int limit = Math.min(lines.length, 20);
        boolean inFrontmatter = false;
        for (int i = 0; i < limit; i++) {
            String line = lines[i].trim();
            if (i == 0 && "---".equals(line)) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter) {
                if ("---".equals(line) || "...".equals(line)) {
                    break;
                }
                if (line.startsWith("override:")) {
                    return line.substring("override:".length()).trim().equalsIgnoreCase("true");
                }
            }
        }
        return false;
    }

    private static boolean isEmpty(EntryCandidate c) {
        return c == null || c.getContent() == null || c.getContent().isEmpty();
    }

    private static EntrySelection chosen(EntryCandidate c, String reason) {
        return EntrySelection.builder()
                .source(c.getPath())
                .path(c.getPath())
                .version(c.getVersion())
                .reason(reason)
                .content(c.getContent())
                .build();
    }

    /**
     * semver 比较：入参应为已归一化的 "主.次.修" 三段式（见 {@link #normalizeVersion}）。
     * 解析异常按 0.0.0 处理（不抛出，保持纯函数全值确定性）。
     */
    public static int compareVersions(String left, String right) {
        long[] a = parseSegments(left);
        long[] b = parseSegments(right);
        for (int i = 0; i < 3; i++) {
            int cmp = Long.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * 版本归一化：剥 `v`/`V` 前缀，按 `.` 分段取数字，缺段补 0（1.3 ≡ 1.3.0），多余段忽略。
     * 非数字段按 0 处理。返回 "主.次.修" 三段式。
     */
    public static String normalizeVersion(String raw) {
        long[] seg = parseSegments(raw);
        return seg[0] + "." + seg[1] + "." + seg[2];
    }

    /**
     * 从入口文件内容提取版本声明（设计文档 56 §5.1）。
     * 优先级：frontmatter {@code version:} → 头部 {@code <!-- gewu-entry: v1.3.0 -->} 注释标记。
     * 仅扫描前 20 行；都没有返回 null。解析出非数字内容视为无版本（返回 null，不报错）。
     */
    public static String extractVersion(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        int limit = Math.min(lines.length, 20);

        // frontmatter version:（仅认可出现在 --- 围栏内，防止正文里同名键误伤）
        boolean inFrontmatter = false;
        for (int i = 0; i < limit; i++) {
            String line = lines[i].trim();
            if (i == 0 && "---".equals(line)) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter) {
                if ("---".equals(line) || "...".equals(line)) {
                    break;
                }
                if (line.startsWith("version:")) {
                    String raw = line.substring("version:".length()).trim();
                    return toVersionOrNull(raw);
                }
            }
        }

        // 兼容写法：<!-- gewu-entry: v1.3.0 -->
        // 收紧（CR P2#7）：仅认可"gewu-entry: 后紧跟版本数字"的行，散文提及不误判为版本声明
        for (int i = 0; i < limit; i++) {
            java.util.regex.Matcher m = MARKER_VERSION.matcher(lines[i]);
            if (m.find()) {
                return toVersionOrNull(m.group(1));
            }
        }
        return null;
    }

    private static String toVersionOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String normalized = normalizeVersion(raw);
        if ("0.0.0".equals(normalized) && !raw.matches(".*[1-9].*")) {
            // 归一化后全零且原文无有效数字段 → 视为无版本声明
            return null;
        }
        return normalized;
    }

    private static long[] parseSegments(String raw) {
        long[] seg = {0, 0, 0};
        if (raw == null) {
            return seg;
        }
        String s = raw.trim().replaceFirst("^[vV]", "");
        String[] parts = s.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            String p = parts[i].trim();
            StringBuilder digits = new StringBuilder();
            for (char c : p.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            if (!digits.isEmpty()) {
                // 限长解析（CR P3#16）：超长数字段截断至 10 位，防溢出静默归零
                String d = digits.substring(0, Math.min(10, digits.length()));
                seg[i] = Long.parseLong(d);
            }
        }
        return seg;
    }
}
