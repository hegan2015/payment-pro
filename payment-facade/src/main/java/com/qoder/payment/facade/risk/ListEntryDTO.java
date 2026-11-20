package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-3 名单条目（黑/白/灰 × 用户/商户/卡号/设备/IP）。
 *
 * <p><b>优先级红线</b>：WHITE &gt; BLACK &gt; GRAY。同一请求命中多类名单时，
 * 只要存在有效 WHITE 命中即放行（跳过黑名单拦截与评分升级），并留痕 listHit=WHITE。
 */
@Data
public class ListEntryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    /** BLACK/WHITE/GRAY。 */
    private String listType;
    /** USER/MERCHANT/CARD/DEVICE/IP。 */
    private String valueType;
    private String value;
    /** 命中处置：REJECT/CHALLENGE/PASS/MANUAL_REVIEW；缺省按 listType 推导。 */
    private String action;
    private String reason;
    /** 过期时间戳（毫秒）；null/0=永不过期。 */
    private Long expireAt;
    /** ENABLED/REMOVED。 */
    private String status;
    private Long createdAt;
    private Long updatedAt;
    /** 审计：操作人（网关恒取会话用户）。 */
    private String operator;
}