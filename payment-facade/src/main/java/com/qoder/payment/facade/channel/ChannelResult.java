package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CH-2a 渠道 SPI 统一返回：同步/异步语义归一。
 *
 * <ul>
 *   <li>{@code SUCCESS}：渠道已给出终态成功（同步渠道=预授权就绪，扣款由 TCC Confirm 驱动）；</li>
 *   <li>{@code PROCESSING}：渠道已受理未终态 → 落 channel_trade SUBMITTED，走 CH-1 WAITING/回执/补单路径；</li>
 *   <li>{@code FAILED}：渠道明确拒绝/失败。{@code failoverAllowed=true} 仅限「渠道确未受理」
 *       （连接被拒/发送前失败），此时 CH-3 可安全切换备选渠道；业务拒绝（风控/卡号规则）
 *       与「已受理后超时（状态未知）」一律 false，杜绝重复受理与悬挂单。</li>
 * </ul>
 */
@Data
public class ChannelResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String PROCESSING = "PROCESSING";

    private String status;
    /** 渠道方订单号（异步渠道受理即返回；同步渠道可空）。 */
    private String channelOrderNo;
    /** 失败原因 / 受理说明（原样透出给上游，同步拒绝文案与重构前逐字一致）。 */
    private String reason;
    /** 渠道侧金额（分）：非空且与请求不一致时由调用方按资金护航拒绝。 */
    private Long amount;
    /** 是否允许故障切换（仅「确未受理」为 true）。 */
    private boolean failoverAllowed;
    /** 渠道原始报文留痕（可空）。 */
    private String raw;
    /** 渠道侧原始状态码（CAPTURED/REFUNDED/SUBMITTED/FAILED/VOIDED/NOT_FOUND），供补单判定终态。 */
    private String channelStatus;

    public static ChannelResult success(String channelOrderNo) {
        ChannelResult r = new ChannelResult();
        r.setStatus(SUCCESS);
        r.setChannelOrderNo(channelOrderNo);
        return r;
    }

    public static ChannelResult processing(String channelOrderNo, String reason) {
        ChannelResult r = new ChannelResult();
        r.setStatus(PROCESSING);
        r.setChannelOrderNo(channelOrderNo);
        r.setReason(reason);
        return r;
    }

    /** 业务拒绝：不可切换（切换会掩盖真实拒绝原因，且违背同步路径既有语义）。 */
    public static ChannelResult declined(String reason) {
        ChannelResult r = new ChannelResult();
        r.setStatus(FAILED);
        r.setReason(reason);
        r.setFailoverAllowed(false);
        return r;
    }

    /** 基础设施失败且渠道确未受理：可安全切换备选渠道。 */
    public static ChannelResult notAccepted(String reason) {
        ChannelResult r = new ChannelResult();
        r.setStatus(FAILED);
        r.setReason(reason);
        r.setFailoverAllowed(true);
        return r;
    }

    public boolean isProcessing() {
        return PROCESSING.equals(status);
    }

    public boolean isSuccess() {
        return SUCCESS.equals(status);
    }

    public boolean isFailed() {
        return FAILED.equals(status);
    }
}