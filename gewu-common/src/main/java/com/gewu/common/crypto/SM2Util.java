package com.gewu.common.crypto;

import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * SM2 椭圆曲线公钥密码算法工具 (GB/T 32918-2016).
 *
 * <p>使用 sm2p256v1 曲线，提供数字签名生成与验证。
 */
public final class SM2Util {

    private static final String CURVE_NAME = "sm2p256v1";
    private static final X9ECParameters EC_PARAMS = GMNamedCurves.getByName(CURVE_NAME);
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            EC_PARAMS.getCurve(), EC_PARAMS.getG(), EC_PARAMS.getN(), EC_PARAMS.getH());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    private SM2Util() {}

    public static byte[] sign(byte[] privateKey, byte[] data) {
        try {
            ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(
                    new BigInteger(1, privateKey), DOMAIN);
            SM2Signer signer = new SM2Signer();
            signer.init(true, new org.bouncycastle.crypto.params.ParametersWithRandom(privKey, SECURE_RANDOM));
            signer.update(data, 0, data.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new IllegalStateException("SM2 签名失败", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            ECPoint point = EC_PARAMS.getCurve().decodePoint(publicKey);
            ECPublicKeyParameters pubKey = new ECPublicKeyParameters(point, DOMAIN);
            SM2Signer verifier = new SM2Signer();
            verifier.init(false, pubKey);
            verifier.update(data, 0, data.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            throw new IllegalStateException("SM2 验签失败", e);
        }
    }

    public static BigInteger[] generateKeyPair() {
        BigInteger privateKey = new BigInteger(EC_PARAMS.getN().bitLength(), SECURE_RANDOM)
                .mod(EC_PARAMS.getN());
        ECPoint publicKey = EC_PARAMS.getG().multiply(privateKey).normalize();
        return new BigInteger[]{privateKey, publicKey.getAffineXCoord().toBigInteger()};
    }
}