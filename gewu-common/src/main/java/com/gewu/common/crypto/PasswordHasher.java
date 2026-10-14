package com.gewu.common.crypto;

import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希工具 — PBKDF2WithHmacSM3（UCSS M-6 升级）.
 *
 * <p>双格式兼容（渐进迁移）：
 * <ul>
 *   <li>新格式 {@code PBKDF2$<iterations>$<saltB64>$<hashB64>} — PBKDF2WithHmacSM3（BouncyCastle），
 *       默认 100_000 轮，满足 KDF 基线；新哈希一律产出此格式。</li>
 *   <li>旧格式 {@code <iterations>$<saltHex>$<hashHex>} — 历史 SM3 串接迭代（1 万轮），
 *       仅保留验证能力；verify 成功后由调用方（登录流程）触发透明重哈希升级（needsUpgrade）。</li>
 * </ul>
 */
public final class PasswordHasher {

    /** 新格式算法标识（首段） */
    private static final String ALG_TAG = "PBKDF2";
    /** PBKDF2 迭代基线（OWASP 2023 建议 PBKDF2-HmacSHA256 ≥ 60 万；SM3 吞吐更高取 10 万起步并集中可调） */
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int SALT_LENGTH = 32;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    private PasswordHasher() {}

    /** 新密码哈希：一律产出 PBKDF2 新格式。 */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        return ALG_TAG + "$" + PBKDF2_ITERATIONS + "$"
                + java.util.Base64.getEncoder().encodeToString(salt) + "$"
                + java.util.Base64.getEncoder().encodeToString(dk);
    }

    /** 双格式验证：按首段分派，旧格式保留验证能力（配合 needsUpgrade 渐进升级）。 */
    public static boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        String[] parts = storedHash.split("\\$");
        try {
            if (ALG_TAG.equals(parts[0]) && parts.length == 4) {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = java.util.Base64.getDecoder().decode(parts[2]);
                byte[] expected = java.util.Base64.getDecoder().decode(parts[3]);
                byte[] computed = pbkdf2(password.toCharArray(), salt, iterations);
                return java.security.MessageDigest.isEqual(computed, expected);
            }
            if (parts.length == 3 && parts[0].matches("\\d+")) {
                // 旧格式：SM3 串接迭代（历史数据验证，不做新写入）
                int iterations = Integer.parseInt(parts[0]);
                byte[] salt = SM4Util.hexToBytes(parts[1]);
                String computed = legacySm3Chain(password, salt, iterations);
                return computed.equalsIgnoreCase(parts[2]);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 是否需要升级到 PBKDF2 新格式（登录验证成功后调用，命中即重哈希）。 */
    public static boolean needsUpgrade(String storedHash) {
        return storedHash == null || !storedHash.startsWith(ALG_TAG + "$");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSM3", "BC");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2WithHmacSM3 初始化失败", e);
        }
    }

    /** 旧格式算法本体（仅验证用）：SM3 加盐后十六进制串接迭代。 */
    private static String legacySm3Chain(String password, byte[] salt, int iterations) {
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
