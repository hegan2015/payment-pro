package com.qoder.payment.settle.bank;

/**
 * ST-4 出款渠道 SPI（结算侧）。<b>沙箱实现，不接真实银行</b>。
 *
 * <p>A 泳道的 {@code ChannelAdapter.withdraw} 落地后，本 SPI 可由适配器桥接到渠道域；
 * 当前实现只调 {@code ChannelFacade}（只读查询/回执）+ 本地沙箱，<b>不改 channel 域</b>。
 */
public interface BankPayoutAdapter {

    /** 渠道编码（落 withdraw_order.channel_code）。 */
    String code();

    /** 受理出款（幂等：同一 bizNo 重复提交返回同一结果）。 */
    PayoutResult submit(PayoutCommand command);

    /** 出款查询（补单收敛用，确定性、重启安全）。 */
    PayoutResult query(PayoutCommand command);

    /** 出款指令。createdAt 用于沙箱异步延迟判定（无内存状态，重启后结论不变）。 */
    record PayoutCommand(String bizNo, String accountId, long amount, String bankCardNo, long createdAt) {
    }

    /** 出款结果：PROCESSING（在途）/ SUCCESS（到账）/ FAILED（失败）。 */
    record PayoutResult(String status, String channelOrderNo, String reason) {

        public static PayoutResult processing(String orderNo) {
            return new PayoutResult("PROCESSING", orderNo, null);
        }

        public static PayoutResult success(String orderNo) {
            return new PayoutResult("SUCCESS", orderNo, null);
        }

        public static PayoutResult failed(String orderNo, String reason) {
            return new PayoutResult("FAILED", orderNo, reason);
        }
    }
}