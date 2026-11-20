package com.qoder.payment.facade.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * A-2 绑卡工具（member/account 两域共用，契约 jar additive）：
 * Luhn 校验、卡号脱敏（只留后四位）、SHA-256 哈希、手机号格式、沙箱卡 BIN 归属。
 * <b>红线</b>：卡号明文绝不落库/落日志——入库前一律 hash + mask。
 */
public final class BankCardUtil {

    /** 沙箱卡 BIN → 银行（真实 BIN 库属 CH-6/A 泳道，此处仅演示映射）。 */
    private static final Map<String, String> BIN_BANKS = Map.of(
            "622202", "工商银行", "622848", "农业银行", "621700", "建设银行",
            "622588", "招商银行", "622155", "平安银行", "625958", "浦发银行");

    private BankCardUtil() {
    }

    /** 卡号格式：12-19 位数字且 Luhn 通过。 */
    public static boolean validCardNo(String cardNo) {
        if (cardNo == null || !cardNo.matches("\\d{12,19}")) {
            return false;
        }
        return luhn(cardNo);
    }

    /** Luhn 校验位算法。 */
    public static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** 卡号脱敏：**** **** **** 后四位（不足 4 位全掩码）。 */
    public static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 4) {
            return "****";
        }
        return "**** **** **** " + cardNo.substring(cardNo.length() - 4);
    }

    /** 手机号脱敏：138****5678。 */
    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    /** 大陆手机号格式（沙箱）。 */
    public static boolean validMobile(String mobile) {
        return mobile != null && mobile.matches("1[3-9]\\d{9}");
    }

    /** 沙箱卡 BIN（前 6 位）→ 银行名；未知 BIN 返回 SANDBOX_BANK。 */
    public static String binBank(String cardNo) {
        if (cardNo == null || cardNo.length() < 6) {
            return "SANDBOX_BANK";
        }
        return BIN_BANKS.getOrDefault(cardNo.substring(0, 6), "SANDBOX_BANK");
    }

    /** SHA-256 _hex（敏感要素哈希存储用；不可逆，库内不落明文）。 */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}