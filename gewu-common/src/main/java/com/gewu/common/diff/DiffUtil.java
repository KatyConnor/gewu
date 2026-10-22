package com.gewu.common.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 unified diff（设计文档 58 §10 P3-4 二期：导入差异预览的内容级视图）.
 *
 * <p>轻量实现：先裁剪公共前/后缀，再对中段做哈希分桶 LCS。中段超过
 * {@value #MAX_DIFF_LINES} 行时降级为"行数摘要"模式（防 O(n·m) 爆炸）。
 * 输出为 unified 格式（@@ -a,b +c,d @@ 与 +/-/空格 行），可直接渲染。
 */
public final class DiffUtil {

    /** 触发摘要降级的中段行数上限 */
    public static final int MAX_DIFF_LINES = 2000;
    /** unified 上下文行数 */
    public static final int CONTEXT = 3;
    /** 输出最大行数默认值 */
    public static final int DEFAULT_MAX_OUTPUT_LINES = 400;

    private DiffUtil() {
    }

    /**
     * 生成 unified diff 文本（无文件头，仅 hunks）。两内容相同时返回空串。
     *
     * @param maxLines 输出最大行数（超限截断并追加标注）
     */
    public static String unifiedDiff(String before, String after, int maxLines) {
        List<String> a = splitLines(before);
        List<String> b = splitLines(after);

        int pre = commonPrefix(a, b);
        int suf = commonSuffix(a, b, pre);
        List<String> aMid = a.subList(pre, a.size() - suf);
        List<String> bMid = b.subList(pre, b.size() - suf);

        if (aMid.isEmpty() && bMid.isEmpty()) {
            return "";
        }
        if (aMid.size() > MAX_DIFF_LINES || bMid.size() > MAX_DIFF_LINES) {
            return "@@ 摘要 @@\n内容差异过大，未生成逐行 diff"
                    + "（删除 " + aMid.size() + " 行 / 新增 " + bMid.size() + " 行）\n";
        }

        // 变更 op 列表：0=同 1=删 2=增（idx 分别指 a/b 中段）
        List<int[]> ops = lcsOps(aMid, bMid);
        List<Integer> changes = new ArrayList<>();
        for (int t = 0; t < ops.size(); t++) {
            if (ops.get(t)[0] != 0) {
                changes.add(t);
            }
        }
        if (changes.isEmpty()) {
            return "";
        }

        // 变更分组：相邻变更间距 ≤ 2×CONTEXT 时并入同一 hunk
        StringBuilder sb = new StringBuilder();
        int g = 0;
        int out = 0;
        while (g < changes.size() && out < maxLines) {
            int first = changes.get(g);
            int last = first;
            while (g + 1 < changes.size()
                    && changes.get(g + 1) - changes.get(g) <= 2 * CONTEXT) {
                g++;
                last = changes.get(g);
            }
            int start = Math.max(0, first - CONTEXT);
            int end = Math.min(ops.size(), last + CONTEXT + 1);

            // hunk 头：a/b 起止行号（1-based，a/b 中段偏移 + 公共前缀）
            int aFrom = pre + start + 1, aCount = 0;
            int bFrom = pre + start + 1, bCount = 0;
            List<String> body = new ArrayList<>();
            for (int t = start; t < end && out < maxLines; t++, out++) {
                int[] op = ops.get(t);
                String text = op[0] == 2 ? bMid.get(op[2]) : aMid.get(op[1]);
                body.add((op[0] == 1 ? "-" : op[0] == 2 ? "+" : " ") + text);
                if (op[0] != 2) {
                    aCount++;
                }
                if (op[0] != 1) {
                    bCount++;
                }
            }
            sb.append("@@ -").append(aFrom).append(',').append(aCount)
                    .append(" +").append(bFrom).append(',').append(bCount).append(" @@\n");
            for (String line : body) {
                sb.append(line).append('\n');
            }
            g++;
        }
        if (out >= maxLines) {
            sb.append("…（diff 超过 ").append(maxLines).append(" 行截断）\n");
        }
        return sb.toString();
    }

    /** 中段 LCS → 操作序列 [type, aIdx, bIdx]：0=同 1=删(a) 2=增(b)。 */
    private static List<int[]> lcsOps(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<int[]> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new int[]{0, i++, j++});
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new int[]{1, i++, -1});
            } else {
                ops.add(new int[]{2, -1, j++});
            }
        }
        while (i < n) {
            ops.add(new int[]{1, i++, -1});
        }
        while (j < m) {
            ops.add(new int[]{2, -1, j++});
        }
        return ops;
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && a.get(i).equals(b.get(i))) {
            i++;
        }
        return i;
    }

    private static int commonSuffix(List<String> a, List<String> b, int from) {
        int i = a.size() - 1, j = b.size() - 1, n = 0;
        while (i >= from && j >= from && a.get(i).equals(b.get(j))) {
            i--;
            j--;
            n++;
        }
        return n;
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(s.split("\n", -1)));
    }
}
