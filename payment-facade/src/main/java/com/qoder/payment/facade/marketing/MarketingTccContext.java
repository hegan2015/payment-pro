package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 营销 TCC 上下文（P-7 支付宝式：营销核销纳入支付全局事务边界，资金模型「直补商户」）。
 *
 * <p><b>原子性设计（根治 subsidy-at-Try 缺陷）</b>：营销 Try <b>只本地预占权益</b>（券/红包 FROZEN、
 * 积分冻结、活动预算抢占），<b>不做任何远程资金调用</b>——Try 失败时模板回滚本地事务即干净释放，无远程补贴滞留。
 * 补贴与分摊在 <b>Confirm</b> 落（Confirm 幂等可重试，崩溃恢复续驱必达，无空回滚滞留）。
 *
 * <p><b>资金模型（直补商户）</b>：accounting 分支 Try 冻结用户<b>净实付</b>（订单额−Σ抵扣）、Confirm 过账
 * {@code D 用户净实付 / C 商户(净实付−fee) / C SYS_FEE fee}；marketing 分支 Confirm 核销权益 +
 * 补贴 {@code D SYS_MARKETING / C 商户抵扣}（MKT 凭证）+ USE 分摊。商户实收=(净实付−fee)+抵扣=订单额−fee。
 * Cancel：营销本地释放预占（无资金可滞留），accounting 解冻净实付。守恒：订单额=净实付+抵扣。
 */
@Data
public class MarketingTccContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String orderId;
    private String memberId;
    /** 用户付款 BALANCE 户（accounting 冻结净实付的扣款户）。 */
    private String accountId;
    /** 商户账户（营销补贴直补商户的入账户）。 */
    private String merchantAccountId;
    private long orderAmount;
    private List<Pick> picks = new ArrayList<>();

    @Data
    public static class Pick implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String source;
        private String refId;
        private Long amount;
        private Long points;
    }
}
