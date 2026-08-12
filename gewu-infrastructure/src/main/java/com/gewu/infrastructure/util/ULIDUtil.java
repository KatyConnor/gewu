package com.gewu.infrastructure.util;

import java.security.SecureRandom;

/**
 * ULID 生成工具 — 生成 26 字符的 ULID 标识符.
 */
public final class ULIDUtil {

    private static final char[] CROCKFORD = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
            'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'V', 'W', 'X', 'Y', 'Z'
    };

    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile long lastTime = 0;
    private static volatile long lastEntropy = 0;

    private ULIDUtil() {}

    public static String next() {
        long time = System.currentTimeMillis();
        if (time <= lastTime) {
            time = lastTime + 1;
        }
        lastTime = time;
        long entropy = RANDOM.nextLong() & 0xFFFFFFFFFFFFL;
        lastEntropy = entropy;

        StringBuilder sb = new StringBuilder(26);
        // 时间戳部分（10 字符）
        for (int i = 9; i >= 0; i--) {
            sb.insert(0, CROCKFORD[(int) ((time >> (i * 5)) & 0x1F)]);
        }
        // 随机部分（16 字符）
        for (int i = 9; i >= 0; i--) {
            sb.append(CROCKFORD[(int) ((entropy >> (i * 5)) & 0x1F)]);
        }
        return sb.toString();
    }
}
