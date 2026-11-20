package com.qoder.payment.facade.notify;

import java.util.List;

/**
 * N-3 通知事件类型目录（additive）。
 *
 * <p><b>兼容红线（§0.7）</b>：{@link #PAY_SUCCESS} / {@link #REFUND_SUCCESS} 为<b>冻结事件</b>
 * （{@code legacy=true}）——通知 body 就是 {@code NotifyRequest.payload} 原样序列化，
 * 不加 envelope、不改字段顺序、不改字段名，ride-pro 靠回调留痕逐字节不变。
 *
 * <p>新增事件一律走统一 envelope：{@code {event, bizId, timestamp, payload:{...}}}，
 * 签名仍由 NotifySigner 对<b>整个 body</b> + "\n" + timestamp + "\n" + nonce 计算（与冻结事件同一套机制），
 * 签名三要素头 additive。幂等键仍为 {@code event:bizId}，各事件类型独立去重。
 *
 * <p>未在本目录登记的 event 字符串按冻结事件处理（原样透传），保证既有调用方零影响。
 */
public enum NotifyEvent {

    /** 支付成功（冻结：body=payload 原样）。 */
    PAY_SUCCESS(true, "支付成功", List.of()),
    /** 退款成功（冻结：body=payload 原样）。 */
    REFUND_SUCCESS(true, "退款成功", List.of()),
    /** 分账成功（P-3/ST-3）。 */
    SHARE_SUCCESS(false, "分账成功", List.of("paymentId", "shareId", "amount")),
    /** 分账回退。 */
    SHARE_BACK(false, "分账回退", List.of("shareId", "amount")),
    /** 结算批次完成（ST-1）。 */
    SETTLE_BATCH(false, "结算批次完成", List.of("batchId", "amount")),
    /** 签约成功（X-4）。 */
    SIGN_SUCCESS(false, "签约成功", List.of("agreementNo")),
    /** 解约（X-4）。 */
    SIGN_CANCEL(false, "签约解约", List.of("agreementNo")),
    /** 差错单开立（X-1）。 */
    DISPUTE_OPENED(false, "差错单开立", List.of("disputeId", "type"));

    private final boolean legacy;
    private final String description;
    private final List<String> requiredFields;

    NotifyEvent(boolean legacy, String description, List<String> requiredFields) {
        this.legacy = legacy;
        this.description = description;
        this.requiredFields = requiredFields;
    }

    /** true=冻结事件，body 逐字节保持 payload 原样（不加 envelope）。 */
    public boolean isLegacy() {
        return legacy;
    }

    public String getDescription() {
        return description;
    }

    /** 新事件 payload 必填字段（模板校验）；冻结事件为空列表（不校验，保持既有宽松语义）。 */
    public List<String> getRequiredFields() {
        return requiredFields;
    }

    /** 未登记事件返回 null（调用方按冻结事件原样透传）。 */
    public static NotifyEvent of(String event) {
        if (event == null || event.isBlank()) {
            return null;
        }
        for (NotifyEvent candidate : values()) {
            if (candidate.name().equals(event)) {
                return candidate;
            }
        }
        return null;
    }
}