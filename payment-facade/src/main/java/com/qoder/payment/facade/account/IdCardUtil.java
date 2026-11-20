package com.qoder.payment.facade.account;

/**
 * M-2 身份证要素工具（沙箱校验，不接真实公安）：18 位 + GB11643 校验位 + 出生日期合法性。
 * 敏感字段脱敏：前 3 位 + *********** + 后 4 位。member/account 两域共用。
 */
public final class IdCardUtil {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCardUtil() {
    }

    /** 18 位身份证校验位核验（含出生日期合法性）。 */
    public static boolean valid(String idCardNo) {
        if (idCardNo == null || !idCardNo.matches("\\d{17}[0-9Xx]")) {
            return false;
        }
        int year = Integer.parseInt(idCardNo.substring(6, 10));
        int month = Integer.parseInt(idCardNo.substring(10, 12));
        int day = Integer.parseInt(idCardNo.substring(12, 14));
        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCardNo.charAt(i) - '0') * WEIGHTS[i];
        }
        char expected = CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(idCardNo.charAt(17));
        return expected == actual;
    }

    /** 身份证脱敏：前 3 位 + *********** + 后 4 位。 */
    public static String mask(String idCardNo) {
        if (idCardNo == null || idCardNo.length() < 8) {
            return "***";
        }
        return idCardNo.substring(0, 3) + "***********" + idCardNo.substring(idCardNo.length() - 4);
    }

    /** 姓名脱敏：张*三 / 欧阳**（首字保留，其余掩码）。 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "***";
        }
        if (name.length() == 1) {
            return "*";
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}