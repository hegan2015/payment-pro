package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-5 设备指纹画像（沙箱采集，不接真实设备 SDK）。
 *
 * <p><b>隐私合规</b>：{@link #deviceId} 是<b>脱敏存储键</b>（默认 SHA-256 前 24 位，前缀 D），
 * {@link #deviceMask} 是首尾保留的展示掩码；<b>原始设备标识永不出现在本 DTO</b>。
 * 留存期由 {@code risk.device.retention-days} 配置，到期只做匿名化（不 DELETE 历史行）。
 */
@Data
public class DeviceProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 脱敏存储键（mask-enabled=true 时为哈希键，false 时为原始 id）。 */
    private String deviceId;
    /** 展示掩码：前 4 + **** + 后 4。 */
    private String deviceMask;
    /** 端侧属性指纹（os|model|ua 的哈希），属性变化即漂移。 */
    private String fingerprintHash;
    /** 稳定性：STABLE / DRIFTING / UNSTABLE / UNKNOWN。 */
    private String stability;
    /** 属性漂移次数（不同属性组合出现次数 - 1）。 */
    private Integer driftCount;
    private Long firstSeen;
    private Long lastSeen;
    /** 同设备关联账户数（一设备多账户=风险信号）。 */
    private Integer accountCount;
    /** 画像风险分（0~100，接入既有 DEVICE 因子，不另起评分体系）。 */
    private Integer riskScore;
    /** 是否判定为风险设备（阈值可配）。 */
    private Boolean risky;
    /** 标签 JSON（MULTI_ACCOUNT/UNSTABLE/BURST 等）。 */
    private String tags;
    /** 判定说明（可解释）。 */
    private String detail;
    /** 留存到期时间（毫秒）；到期后匿名化。 */
    private Long retentionUntil;
    /** 是否已按留存期匿名化。 */
    private Boolean expired;
    private Long createdAt;
    private Long updatedAt;
}