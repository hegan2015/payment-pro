package com.qoder.payment.facade.marketing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * X-3a 领取请求（模板 → 会员）。幂等键 {@code (CLAIM, templateId:memberId:bizNo)} +
 * {@code marketing_claim} UNIQUE(template_id, member_id, biz_no)；每人限领与库存在同一事务内原子校验。
 */
@Data
public class ClaimRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String templateId;
    private String memberId;
    private String bizNo;
    private String operator;
}