package com.gewu.common.entry;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 入口调度文件 `@path` import 展开（设计文档 56 §10 P2）.
 *
 * <p>语义对齐 Claude Code：入口文件中整行 {@code @relative/path.md} 展开为
 * 所引用文件的内容（相对入口文件所在目录）。约束：
 * <ul>
 *   <li>最多展开 {@value #MAX_IMPORTS} 处，超出的 import 保留原文并标注；</li>
 *   <li>只展开一层（import 内容不再递归展开），杜绝 import 链放大；</li>
 *   <li>被引文件不存在/读取失败时保留 import 原行并标注（不中断）；</li>
 *   <li>同一相对路径只展开一次（去重兼防环）。</li>
 * </ul>
 *
 * <p>reader 由运行时提供（平台=沙箱读取，桌面=本地 FS），返回 null 视为不存在。
 */
public final class EntryImportExpander {

    public static final int MAX_IMPORTS = 5;

    /** 读取被引文件：参数（入口文件所在目录, 相对路径），返回内容或 null。 */
    public interface ImportReader {
        String read(String baseDir, String relativePath);
    }

    private EntryImportExpander() {
    }

    /**
     * 展开 content 中的 @path import.
     *
     * @param content 入口文件内容
     * @param baseDir 入口文件所在目录（相对路径的解析基准）
     * @param reader  文件读取回调
     * @return 展开后的内容（无 import 时原样返回）
     */
    public static String expand(String content, String baseDir, ImportReader reader) {
        if (content == null || content.isEmpty() || reader == null
                || !content.contains("@")) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length() + 256);
        Set<String> expanded = new HashSet<>();
        int count = 0;
        boolean changed = false;
        for (String line : lines) {
            String rel = parseImportPath(line);
            if (rel == null || !expanded.add(rel) || count >= MAX_IMPORTS) {
                out.append(line).append('\n');
                continue;
            }
            count++;
            String imported = reader.read(baseDir, rel);
            if (imported == null || imported.isEmpty()) {
                out.append(line).append("  <!-- import 未找到 -->\n");
            } else {
                out.append("<!-- import: ").append(rel).append(" -->\n")
                        .append(imported.strip()).append("\n<!-- /import: ").append(rel).append(" -->\n");
            }
            changed = true;
        }
        return changed ? out.substring(0, out.length() - 1) : content;
    }

    /** 整行 import 识别：行内容恰为 @相对路径（无空格分隔的其余 token）。 */
    private static String parseImportPath(String line) {
        String t = line == null ? "" : line.trim();
        if (t.length() < 2 || t.charAt(0) != '@') {
            return null;
        }
        String path = t.substring(1).trim();
        if (path.isEmpty() || path.contains(" ") || path.contains("..")
                || path.startsWith("/") || path.contains("\\")) {
            return null;
        }
        return path;
    }
}
