package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * CH-4 渠道对账文件（channel_bill）+ 明细（channel_bill_detail）+ 逐笔比对差异。
 *
 * <p>文件格式：CSV（表头 + 明细行 + {@code #TOTAL} 汇总行），由适配器 downloadBill 生成/下载，
 * 落 {@code data/bills/<channelCode>/<bizDate>.csv}（双 profile 同构，docker 亦落宿主卷）。
 * 幂等键：UNIQUE(channel_code,biz_date) + UNIQUE(bill_id,channel_order_no)。
 */
@Data
public class ChannelBillDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String channelCode;
    private String bizDate;
    private String filePath;
    /** 文件声明笔数/金额（#TOTAL 行），解析后与明细汇总校验。 */
    private Long totalCount;
    private Long totalAmount;
    /** DOWNLOADED / PARSED / FAILED。 */
    private String status;
    private Long parsedCount;
    private Long parsedAmount;
    /** 逐笔比对：一致笔数 / 差异笔数 / 建差错单数。 */
    private Long matchedCount;
    private Long diffCount;
    private Long disputeCount;
    private String message;
    private Long createdAt;
    private Long parsedAt;
    private List<Detail> details = new ArrayList<>();
    private List<Diff> diffs = new ArrayList<>();

    @Data
    public static class Detail implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long id;
        private Long billId;
        private String channelOrderNo;
        private String bizNo;
        /** PAY / REFUND。 */
        private String type;
        private Long amount;
        /** 渠道侧终态：CAPTURED / REFUNDED / FAILED。 */
        private String status;
        private String raw;
        /** 比对结论：MATCH / AMOUNT_DIFF / STATUS_DIFF / LOCAL_MISSING / TYPE_DIFF。 */
        private String matchResult;
        private Long disputeId;
    }

    @Data
    public static class Diff implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String channelOrderNo;
        private String bizNo;
        /** AMOUNT_DIFF / STATUS_DIFF / TYPE_DIFF / LOCAL_MISSING / BILL_MISSING。 */
        private String diffType;
        private Long billAmount;
        private Long localAmount;
        private String billStatus;
        private String localStatus;
        private Long disputeId;
        private String reason;
    }
}