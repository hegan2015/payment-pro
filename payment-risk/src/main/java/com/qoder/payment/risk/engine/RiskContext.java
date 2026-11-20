package com.qoder.payment.risk.engine;

import com.qoder.payment.facade.risk.RiskCheckRequest;
import com.qoder.payment.risk.quota.QuotaCounter;
import com.qoder.payment.risk.service.RateLimiter;
import com.ql.util.express.DefaultContext;
import lombok.Data;

import java.util.Set;
import java.util.function.BiPredicate;

/**
 * R-1 规则上下文：QLExpress 可见变量 + 自定义函数所需的能力引用（每次请求新建，无跨请求状态）。
 *
 * <p>可见变量：{@code amount / payerId / merchantId / method / cardBin / deviceId / ip /
 * scene / channelCode / hour}。缺省值一律「安全空值」（amount=0、字符串=""），
 * 避免 null 参与运算触发引擎异常（那会走护栏放行分支，噪声告警）。
 */
@Data
public class RiskContext {

    private String payerId = "";
    private String merchantId = "";
    private String method = "";
    private String cardNo = "";
    private String cardBin = "";
    private String deviceId = "";
    private String ip = "";
    private String scene = "PAY";
    private String channelCode = "";
    private String traceId;
    private String bizNo;
    private long amount;
    private int hour;
    private long now = System.currentTimeMillis();

    /** 限频窗口内笔数（只读，不消耗额度）。 */
    private RateLimiter rateLimiter;
    /** 限额累计只读查询。 */
    private QuotaCounter quotaCounter;
    /** 名单命中查询：(listType, valueType + ":" + value) → 是否命中有效条目。 */
    private BiPredicate<String, String> listLookup;
    private Set<String> riskDevices = Set.of();
    private Set<String> riskIps = Set.of();
    /** 金额偏离倍数（本笔 / 历史均额；无历史=0）。 */
    private double amountDeviation;

    // ---------- R-5 设备/IP 画像观测位（additive；默认影子开关关闭时恒为安全空值） ----------
    /** 端侧沙箱属性（参与指纹稳定性判定，可选）。 */
    private String deviceOs = "";
    private String deviceModel = "";
    private String deviceUa = "";
    private boolean deviceProfileLoaded;
    private int deviceAccountCount;
    private int deviceDriftCount;
    private String deviceStability = "UNKNOWN";
    private int deviceRiskScore;
    /** 画像综合判定：是否风险设备（接入既有 DEVICE 因子与 deviceRisk 函数）。 */
    private boolean deviceProfileRisk;
    private String deviceProfileDetail = "";
    private boolean ipProfileLoaded;
    private int ipAccountCount;
    private int ipRejectCount;
    private boolean ipProxy;
    private boolean ipIdc;
    private String ipCountry = "";
    private String ipRegion = "";
    /** 画像综合判定：是否风险 IP（接入既有 IP 因子与 ipRisk 函数）。 */
    private boolean ipProfileRisk;
    private String ipProfileDetail = "";

    /** R-6 AML 聚合只读能力（由 AmlService 注入；null 时函数一律返回安全值）。 */
    private AmlLookup amlLookup;

    /** AML 聚合/名单只读 SPI（函数内部异常一律吞掉返回安全值，绝不让规则误命中）。 */
    public interface AmlLookup {
        long dayAmount(String payerId);

        long dayCount(String payerId);

        boolean watchlistHit(String listType, String subject);
    }

    public static RiskContext from(RiskCheckRequest r) {
        RiskContext c = new RiskContext();
        if (r == null) {
            return c;
        }
        c.payerId = nz(r.getPayerId());
        c.merchantId = nz(r.getMerchantId());
        c.method = nz(r.getMethod());
        c.cardNo = nz(r.getCardNo());
        c.cardBin = nz(r.getCardBin());
        if (c.cardBin.isEmpty() && c.cardNo.length() >= 6) {
            c.cardBin = c.cardNo.substring(0, 6);
        }
        c.deviceId = nz(r.getDeviceId());
        c.ip = nz(r.getIp());
        c.scene = r.getScene() == null || r.getScene().isBlank() ? "PAY" : r.getScene().trim().toUpperCase();
        c.channelCode = nz(r.getChannelCode());
        c.traceId = r.getTraceId();
        c.bizNo = r.getBizNo();
        c.amount = r.getAmount() == null ? 0L : r.getAmount();
        c.hour = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        c.deviceOs = nz(r.getDeviceOs());
        c.deviceModel = nz(r.getDeviceModel());
        c.deviceUa = nz(r.getDeviceUa());
        return c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** QLExpress 变量表。 */
    public DefaultContext<String, Object> variables() {
        DefaultContext<String, Object> v = new DefaultContext<>();
        v.put("amount", amount);
        v.put("payerId", payerId);
        v.put("merchantId", merchantId);
        v.put("method", method);
        v.put("cardBin", cardBin);
        v.put("deviceId", deviceId);
        v.put("ip", ip);
        v.put("scene", scene);
        v.put("channelCode", channelCode);
        v.put("hour", hour);
        // R-5/R-6 additive 变量（缺省安全空值，既有规则表达式不受影响）
        v.put("deviceAccountCount", deviceAccountCount);
        v.put("deviceDriftCount", deviceDriftCount);
        v.put("deviceStability", deviceStability);
        v.put("ipAccountCount", ipAccountCount);
        v.put("ipRejectCount", ipRejectCount);
        v.put("ipProxy", ipProxy);
        v.put("ipIdc", ipIdc);
        v.put("ipCountry", ipCountry);
        v.put("ipRegion", ipRegion);
        return v;
    }
}