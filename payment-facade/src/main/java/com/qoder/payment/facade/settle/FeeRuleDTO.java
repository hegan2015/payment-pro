package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * ST-2 分层费率规则：商户 / 交易类型（productType）/ 支付方式（method）三层，
 * 每层支持通配 {@code *}。feeType=FIXED（feeBps）/ TIERED（tiers）/ SHARE（shareRatio 供 P-3 分账）。
 *
 * <p>DB 权威 + Apollo {@code settle-fee} namespace 热更兜底；缺省回退 {@code payment.fee-bps=60}。
 * tiersJson 为 tiers 的持久化形态（CLOB/LONGTEXT），只读回显。
 */
@Data
public class FeeRuleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String merchantId;
    private String productType;
    private String method;
    private String feeType;
    private Integer feeBps;
    private List<FeeTierDTO> tiers;
    private String tiersJson;
    /** 分成比例（基点，供 P-3 分账编排使用；结算侧只登记不计算）。 */
    private Integer shareRatio;
    private String status;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;
}