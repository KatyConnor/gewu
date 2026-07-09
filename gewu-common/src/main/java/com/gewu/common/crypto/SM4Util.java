package com.gewu.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SM4 分组密码算法工具 (GB/T 32907-2016).
 *
 * <p>使用 GCM 模式提供 AEAD (认证加密)，满足设计文档中 SM4-GCM 强制要求。
 * 128 位密钥，96 位 IV，128 位认证标签。
 */
public final class SM4Util {

    private static final String ALGORITHM = "SM4";
    private static final String TRANSFORMATION = "SM4/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    private SM4Util() {}

    public static String encryptHex(byte[] key, byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
            return SM3Util.bytesToHex(combined);
        } catch (Exception e) {
            throw new IllegalStateException("SM4-GCM 加密失败", e);
        }
    }

    public static byte[] decrypt(byte[] key, String encryptedHex) {
        byte[] combined = hexToBytes(encryptedHex);
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("SM4-GCM 解密失败", e);
        }
    }

    public static String decryptToString(byte[] key, String encryptedHex) {
        return new String(decrypt(key, encryptedHex), StandardCharsets.UTF_8);
    }

    public static byte[] generateKey() {
        byte[] key = new byte[16];
        SECURE_RANDOM.nextBytes(key);
        return key;
    }

    public static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}