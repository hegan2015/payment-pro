package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-5 行为序列事件（支付行为时序：短时间多笔 / 异地跳变识别的原始样本）。 */
@Data
public class BehaviorEventDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String payerId;
    /** 脱敏设备存储键。 */
    private String deviceId;
    private String ip;
    private String region;
    private String country;
    /** 动作：PAY/TOPUP/WITHDRAW/TRANSFER/REFUND。 */
    private String action;
    private Long amount;
    private String traceId;
    private Long createdAt;
}