package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ST-5 商户账单/对账单：DAILY（bizDate=当日 00:00）/ MONTHLY（bizDate=当月 1 日 00:00）。
 * 口径与结算/账务一致：gross/fee/net/refund = Σ结算单（跨片顶层 SUM 归并），share = Σ分账明细。
 * 幂等 UNIQUE(merchant_id,biz_date,bill_type)，重复生成不重不改金额。
 */
@Data
public class MerchantBillDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    private Long bizDate;
    private String bizDateText;
    private String billType;
    private Long gross;
    private Long fee;
    private Long net;
    private Long refund;
    private Long share;
    private Integer recordCount;
    private String filePath;
    private String status;
    private Long createdAt;
}