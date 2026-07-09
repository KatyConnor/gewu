package com.gewu.common.crypto;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.nio.charset.StandardCharsets;

/**
 * SM3 密码杂凑算法工具 (GB/T 32905-2016).
 *
 * <p>输出 256 位 (32 字节) 摘要，用于数字签名、口令哈希、完整性校验。
 */
public final class SM3Util {

    private SM3Util() {}

    public static byte[] hash(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    public static byte[] hash(String data) {
        return hash(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String hashHex(String data) {
        return bytesToHex(hash(data));
    }

    public static String hashWithSalt(String data, byte[] salt) {
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[dataBytes.length + salt.length];
        System.arraycopy(dataBytes, 0, combined, 0, dataBytes.length);
        System.arraycopy(salt, 0, combined, dataBytes.length, salt.length);
        return bytesToHex(hash(combined));
    }

    public static boolean verify(String data, String hashHex) {
        return hashHex(data).equalsIgnoreCase(hashHex);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}