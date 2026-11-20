package com.qoder.payment.trade.product;

/**
 * T-3 交易产品枚举（多交易产品路由的产品码）。
 *
 * <p><b>INSTANT 是缺省产品</b>：存量订单（product_type 为 NULL）与不传 productType 的新单
 * 一律按 INSTANT 处理，状态机/分录/幂等键与改造前逐字节一致（ride §0.7 红线）。
 */
public enum TradeProduct {

    /** 即时到账：CREATED→PAYING→PAID→(PARTIAL_REFUNDED/REFUNDED)，付款即入商户账。 */
    INSTANT,
    /** 担保交易：付款入平台担保户（escrow_status=HOLD），确认收货/超时自动确认后打款卖家。 */
    ESCROW,
    /** 预授权：A-3 PREAUTH 冻结→完成扣款（≤冻结额，差额解冻）/撤销全额解冻。 */
    PREAUTH;

    /** 宽容解析：null/空白/未知一律回落 INSTANT（绝不因新增字段破坏既有下单）。 */
    public static TradeProduct of(String value) {
        if (value == null || value.isBlank()) {
            return INSTANT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return INSTANT;
        }
    }

    /** 严格解析（显式指定产品时用）：未知产品码拒绝，避免静默落到错误状态机。 */
    public static TradeProduct parse(String value) {
        if (value == null || value.isBlank()) {
            return INSTANT;
        }
        return valueOf(value.trim().toUpperCase());
    }

    public String code() {
        return name();
    }
}