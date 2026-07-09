package com.gewu.common.ulid;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID (Universally Unique Lexicographically Sortable Identifier) 生成器.
 *
 * <p>26 字符 Crockford Base32 编码，128 位长度，时间戳有序，全局唯一。
 * 布局：48 位毫秒时间戳 + 80 位随机数。
 */
public final class Ulid {

    private static final char[] ENCODE = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_LEN = 10;
    private static final int RAND_LEN = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String next() {
        long timestamp = Instant.now().toEpochMilli();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        return encode(timestamp, random);
    }

    public static String nextMonotonic(String previous) {
        long timestamp = Instant.now().toEpochMilli();
        if (previous != null && previous.length() == 26) {
            long prevTs = decodeTimestamp(previous);
            if (timestamp == prevTs) {
                byte[] prevRand = decodeRandom(previous);
                increment(prevRand);
                return encode(timestamp, prevRand);
            }
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        return encode(timestamp, random);
    }

    private static String encode(long timestamp, byte[] random) {
        char[] chars = new char[TIME_LEN + RAND_LEN];

        for (int i = 9; i >= 0; i--) {
            chars[i] = ENCODE[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }

        BigInteger randInt = new BigInteger(1, random);
        for (int i = 15; i >= 0; i--) {
            int idx = randInt.mod(BigInteger.valueOf(32)).intValue();
            chars[TIME_LEN + i] = ENCODE[idx];
            randInt = randInt.shiftRight(5);
        }

        return new String(chars);
    }

    private static long decodeTimestamp(String ulid) {
        long ts = 0;
        for (int i = 0; i < TIME_LEN; i++) {
            ts = (ts << 5) | decodeChar(ulid.charAt(i));
        }
        return ts;
    }

    private static byte[] decodeRandom(String ulid) {
        BigInteger randInt = BigInteger.ZERO;
        for (int i = TIME_LEN; i < TIME_LEN + RAND_LEN; i++) {
            randInt = randInt.shiftLeft(5).add(BigInteger.valueOf(decodeChar(ulid.charAt(i))));
        }
        byte[] bytes = randInt.toByteArray();
        byte[] result = new byte[10];
        int src = Math.max(0, bytes.length - 10);
        int dst = Math.max(0, 10 - bytes.length);
        int len = Math.min(bytes.length, 10);
        System.arraycopy(bytes, src, result, dst, len);
        return result;
    }

    private static void increment(byte[] rand) {
        for (int i = rand.length - 1; i >= 0; i--) {
            if (rand[i] == (byte) 0xFF) {
                rand[i] = 0;
            } else {
                rand[i]++;
                break;
            }
        }
    }

    private static int decodeChar(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'Z') {
            switch (c) {
                case 'O': return 0;
                case 'I': case 'L': return 1;
                default: return c - 'A' + 10;
            }
        }
        return 0;
    }
}