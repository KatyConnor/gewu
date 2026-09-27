package com.veloflow.engine.commons;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Veloflow ID 生成器——ULID 规范（Crockford Base32，26 字符，时间有序，索引友好）。
 */
public final class VlfId {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private VlfId() {
    }

    public static String next() {
        long timestamp = Instant.now().toEpochMilli();
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);
        // 48bit 时间 + 80bit 随机 = 128bit → 26 字符
        long high = (timestamp & 0xFFFFFFFFFFFFL) << 16
                | (randomness[0] & 0xFF) << 8 | (randomness[1] & 0xFF);
        StringBuilder sb = new StringBuilder(26);
        for (int shift = 100; shift >= 80; shift -= 5) {
            sb.append(ENCODING[(int) ((high >>> shift) & 0x1F)]);
        }
        long low = 0;
        for (int i = 2; i < 10; i++) {
            low = (low << 8) | (randomness[i] & 0xFF);
        }
        for (int shift = 75; shift >= 0; shift -= 5) {
            sb.append(ENCODING[(int) ((low >>> shift) & 0x1F)]);
        }
        return sb.toString();
    }
}
