package com.veloflow.engine.commons;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.util.HexFormat;

/** Veloflow SM3 摘要（GB/T 32905-2016，BouncyCastle 实现）——Webhook token 哈希等 */
public final class VlfSm3 {

    private VlfSm3() {
    }

    public static String hashHex(String data) {
        byte[] input = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SM3Digest digest = new SM3Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return HexFormat.of().formatHex(out);
    }
}
