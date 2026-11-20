package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a 营销模板（红包/优惠券共用，{@code kind=COUPON|RED_PACKET}）。
 *
 * <p>模板是「发放/领取」的口径来源：面额、门槛、有效期、库存、每人限领。
 * 库存扣减走 DB 原子条件更新（{@code issued + n <= total_stock}），跨实例安全，不超发。
 */
@Data
public class MarketingTemplateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板 id（新建可空，由服务端取号；传入即按 id 幂等 upsert）。 */
    private String id;
    /** COUPON / RED_PACKET。 */
    private String kind;
    private String name;
    /** 面额（分）。 */
    private Long amount;
    /** 使用门槛（订单额 ≥ threshold 才可用），红包缺省 0。 */
    private Long threshold;
    /** 总库存（张）；≤0 表示不限量。 */
    private Integer totalStock;
    /** 已发放/已领取张数（原子累加）。 */
    private Integer issued;
    /** 每人限领张数；≤0 表示不限。 */
    private Integer perMemberLimit;
    /** 领取后有效天数（与 expireAt 二选一，expireAt 优先）。 */
    private Integer validDays;
    /** 绝对过期时间（epoch millis）。 */
    private Long expireAt;
    /** ACTIVE / DISABLED。 */
    private String status;
    /** 扩展规则 JSON（预留，本期不解释执行）。 */
    private String rule;
    private String operator;
    private Long createdAt;
    private Long updatedAt;
}