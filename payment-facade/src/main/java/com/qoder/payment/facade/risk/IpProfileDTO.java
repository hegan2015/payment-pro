package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-5 IP 画像（归属地 / 代理 / IDC / 历史风险）。
 *
 * <p><b>离线沙箱数据源</b>：归属地与代理/IDC 判定全部来自内置 CIDR 沙箱表
 * （可经 {@code risk.ip.sandbox.json} 覆盖），<b>不连任何外部威胁情报 API</b>。
 * 历史风险来自本域 {@code risk_decision_log} 的拒绝留痕，非外部数据。
 */
@Data
public class IpProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String ip;
    /** 归属地（沙箱表）。 */
    private String region;
    /** 国家/地区码（沙箱表；未知为空串，跨境规则不把未知当跨境）。 */
    private String country;
    private Boolean proxy;
    private Boolean idc;
    private Integer accountCount;
    /** 历史拒绝次数（本域留痕聚合）。 */
    private Integer rejectCount;
    private Integer riskScore;
    private Boolean risky;
    private String tags;
    private String detail;
    /** 数据源标识：SANDBOX（离线内置表）/ CONFIG（配置覆盖）。 */
    private String source;
    private Long firstSeen;
    private Long lastSeen;
    private Long retentionUntil;
    private Boolean expired;
}