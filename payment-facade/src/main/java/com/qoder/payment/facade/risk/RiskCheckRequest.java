package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 风控校验入参。
 *
 * <p><b>兼容红线（§0.7）</b>：原有 4 字段（payerId/merchantId/amount/method）语义与取值口径
 * 逐字节不变（payment 域现有调用点不改）；R-1~R-4 新增字段全部可选 additive，
 * 缺省 null 时规则/评分因子一律按「无该信息」处理，绝不因缺字段加分或拦截。
 */
@Data
public class RiskCheckRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String payerId;
    private String merchantId;
    private Long amount;
    private String method;

    // ---------- R-1/R-2/R-3/R-4 additive（可选） ----------
    /** 银行卡号（明文仅在域内内存流转，留痕只落掩码）。 */
    private String cardNo;
    /** 卡 BIN（前 6 位）；缺省时由 cardNo 推导。 */
    private String cardBin;
    /** 设备指纹。 */
    private String deviceId;
    /** 客户端 IP。 */
    private String ip;
    /** 场景：PAY（默认）/REFUND/WITHDRAW/TOPUP/TRANSFER。 */
    private String scene;
    /** 渠道编码（渠道维度限额用；BALANCE 无渠道时为 null）。 */
    private String channelCode;
    /** 链路追踪 id（留痕关联）。 */
    private String traceId;
    /** 业务单号（支付单 id 等，留痕/人审建单幂等用）。 */
    private String bizNo;

    // ---------- R-5 设备指纹 additive（可选；端侧沙箱采集，不接真实设备 SDK） ----------
    /** 设备操作系统（参与指纹稳定性判定；缺省不参与漂移计算）。 */
    private String deviceOs;
    /** 设备型号（参与指纹稳定性判定）。 */
    private String deviceModel;
    /** 客户端 UA（参与指纹稳定性判定）。 */
    private String deviceUa;
}