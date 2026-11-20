package com.qoder.payment.common.secret;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 商户密钥落库加密工具（M-1）：AES-GCM，主密钥经 {@link SecretProvider} 取
 * （ref=member-secret-master，local 文件惰性生成 600 / Vault KV v2），DB 只存密文 secret_cipher。
 * <p>密文格式 Base64(12B IV || ciphertext+tag)；主密钥取 UTF-8 字节（32 字符 hex → AES-256）。
 * 明文/主密钥绝不写日志。
 */
public final class SecretCipher {

    public static final String MASTER_KEY_REF = "member-secret-master";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecretCipher() {
    }

    public static String encrypt(String masterKey, String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(masterKey), new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    public static String decrypt(String masterKey, String cipherText) {
        try {
            byte[] raw = Base64.getDecoder().decode(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(masterKey), new GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN));
            return new String(cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密钥解密失败（主密钥不匹配或密文损坏）", e);
        }
    }

    /** 密钥指纹：SHA-256 前 16 位 hex（可对外暴露，不可逆）。 */
    public static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private static SecretKeySpec key(String masterKey) {
        if (masterKey == null || masterKey.length() < 16) {
            throw new IllegalStateException("主密钥缺失或过短 ref=" + MASTER_KEY_REF);
        }
        byte[] bytes = masterKey.getBytes(StandardCharsets.UTF_8);
        byte[] sized = new byte[32];
        System.arraycopy(bytes, 0, sized, 0, Math.min(bytes.length, 32));
        return new SecretKeySpec(sized, "AES");
    }
}