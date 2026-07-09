package com.gewu.common.crypto;

import java.security.SecureRandom;

/**
 * 密码哈希工具 — SM3 加盐多轮迭代.
 *
 * <p>存储格式: {iterations}${salt}${hash}, 迭代 10000 次防暴力破解。
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 10000;
    private static final int SALT_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        String saltHex = SM3Util.bytesToHex(salt);
        return ITERATIONS + "$" + saltHex + "$" + computeHash(password, salt, ITERATIONS);
    }

    public static boolean verify(String password, String storedHash) {
        String[] parts = storedHash.split("\\$");
        if (parts.length != 3) return false;
        int iterations = Integer.parseInt(parts[0]);
        byte[] salt = SM4Util.hexToBytes(parts[1]);
        String computed = computeHash(password, salt, iterations);
        return computed.equalsIgnoreCase(parts[2]);
    }

    private static String computeHash(String password, byte[] salt, int iterations) {
        String current = SM3Util.hashWithSalt(password, salt);
        for (int i = 1; i < iterations; i++) {
            current = SM3Util.hashHex(current);
        }
        return current;
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }
}