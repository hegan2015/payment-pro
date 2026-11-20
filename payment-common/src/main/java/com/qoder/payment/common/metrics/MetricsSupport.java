package com.qoder.payment.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 业务指标统一入口（I-8 可观测）。
 *
 * <p>命名规范：{@code payment.<domain>.<event>}，Prometheus 渲染为
 * {@code payment_<domain>_<event>_total}（Counter 自动加 _total 后缀）；
 * domain 取 spring.application.name 去掉 "payment-" 前缀（如 payment/recon/notify/gateway）。
 * 事件名若以 "<domain>_" 开头则去重（recon 域 recon_dispute → payment_recon_dispute_total）。
 *
 * <p>零外部依赖：直接写 Micrometer {@link Metrics#globalRegistry}，Spring Boot Actuator
 * 会自动把 PrometheusMeterRegistry 挂入全局复合注册表（management.metrics.use-global-registry
 * 默认 true）；未挂任何注册表时（单测/未接 actuator）所有指标为空操作，不影响业务。
 * local 与 docker 双 profile 行为一致，不依赖 Prometheus 服务端。
 *
 * <p>启动时由 {@link MetricsBootstrap} 预注册业务计数器_catalog_（0 值基线序列），
 * 保证各域 /actuator/prometheus 在未发生业务事件时即可被脚本断言指标名存在。
 */
public final class MetricsSupport {

    private static volatile String domain = "app";

    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, Timer> TIMERS = new ConcurrentHashMap<>();
    /** gauge 供应商强引用，防止 Micrometer 弱引用导致 GC 后指标失效。 */
    private static final Map<String, Supplier<Number>> GAUGES = new ConcurrentHashMap<>();

    private MetricsSupport() {
    }

    /** 由启动引导调用：以应用名设定指标域（payment-payment → payment）。 */
    public static void setDomain(String applicationName) {
        String d = applicationName == null ? "" : applicationName.trim().toLowerCase();
        if (d.startsWith("payment-")) {
            d = d.substring("payment-".length());
        }
        d = d.replaceAll("[^a-z0-9_]", "_");
        domain = d.isBlank() ? "app" : d;
    }

    public static String domain() {
        return domain;
    }

    // ---------- 通用 Counter / Timer / Gauge 封装 ----------

    /** 业务计数器（自动加 payment.<domain> 前缀），tags 为 key/value 成对参数。 */
    public static Counter counter(String event, String... tags) {
        String name = metricName(event);
        String key = name + "|" + String.join(",", tags);
        return COUNTERS.computeIfAbsent(key,
                k -> Counter.builder(name).tags(tags).register(Metrics.globalRegistry));
    }

    /** 业务计时器（Prometheus 渲染为 payment_<domain>_<event>_seconds_*）。 */
    public static Timer timer(String event, String... tags) {
        String name = metricName(event);
        String key = name + "|" + String.join(",", tags);
        return TIMERS.computeIfAbsent(key,
                k -> Timer.builder(name).tags(tags).register(Metrics.globalRegistry));
    }

    public static void count(String event, String... tags) {
        counter(event, tags).increment();
    }

    public static void recordMillis(String event, long millis, String... tags) {
        timer(event, tags).record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * 注册 gauge（当前值语义，如 DEAD 积压数）；供应商被强引用保活。
     * 注意：gauge 事件名不得与计数器基名相同（Prometheus collector 按基名注册，
     * 同名 counter/gauge 即使 tag 不同也冲突），如 tcc_dead_total 计数器配 tcc_dead_backlog gauge。
     */
    public static void gauge(String event, Supplier<Number> supplier, String... tags) {
        String name = metricName(event);
        GAUGES.put(name + "|" + String.join(",", tags), supplier);
        Gauge.builder(name, supplier).tags(tags).register(Metrics.globalRegistry);
    }

    // ---------- 业务指标（埋点只调这些方法，一行一事件） ----------

    /** 支付单终态成功（TCC CONFIRMED），tag: method=BALANCE/CARD, biz=TRADE/TOPUP/WITHDRAW。 */
    public static void paySuccess(String method, String bizType) {
        counter("pay_success", "method", nz(method), "biz", nz(bizType), "status", "success").increment();
    }

    /** 支付单终态失败（风控拒绝 / TCC CANCELLED）。 */
    public static void payFail(String method, String bizType) {
        counter("pay_fail", "method", nz(method), "biz", nz(bizType), "status", "fail").increment();
    }

    /** 退款单终态，status=success/fail。 */
    public static void refund(String status) {
        counter("refund", "status", nz(status)).increment();
    }

    /** TCC 补偿重试耗尽置 DEAD（计数语义；payment 域另有 tcc_dead gauge 暴露当前积压）。 */
    public static void tccDead() {
        counter("tcc_dead").increment();
    }

    /** outbox 投递重试耗尽置 DEAD，tag: event=SETTLE_PAY/SETTLE_REFUND/NOTIFY_PAY/NOTIFY_REFUND。 */
    public static void outboxDead(String event) {
        counter("outbox_dead", "event", nz(event)).increment();
    }

    /** 对账单项差错（缺失/不一致/异常），tag: check=核对项名。 */
    public static void reconDispute(String check) {
        counter("recon_dispute", "check", nz(check)).increment();
    }

    /** 对账整轮结果，status=pass/fail（recon_pass==0 告警用）。 */
    public static void reconResult(boolean pass) {
        counter("recon_run", "status", pass ? "pass" : "fail").increment();
    }

    /** 商户通知重试耗尽置 FAILED（商户仍可 PULL 补查）。 */
    public static void notifyDead() {
        counter("notify_dead").increment();
    }

    /** 商户通知送达（2xx）。 */
    public static void notifyDelivered() {
        counter("notify_delivered").increment();
    }

    // ---------- 启动预注册 ----------

    /**
     * 预注册业务计数器 catalog（0 值基线），使各域 /actuator/prometheus 启动即含
     * payment_ 业务指标名，供 verify-metrics.py 与告警规则 absent() 检测使用。
     * 真实 tag 值（如 CARD/TOPUP/中文核对项）在首次事件时自动追加序列。
     */
    public static void preRegisterBusinessMetrics() {
        counter("pay_success", "method", "BALANCE", "biz", "TRADE", "status", "success");
        counter("pay_success", "method", "CARD", "biz", "TRADE", "status", "success");
        counter("pay_fail", "method", "BALANCE", "biz", "TRADE", "status", "fail");
        counter("pay_fail", "method", "CARD", "biz", "TRADE", "status", "fail");
        counter("refund", "status", "success");
        counter("refund", "status", "fail");
        counter("tcc_dead");
        counter("outbox_dead", "event", "SETTLE_PAY");
        counter("outbox_dead", "event", "SETTLE_REFUND");
        counter("outbox_dead", "event", "NOTIFY_PAY");
        counter("outbox_dead", "event", "NOTIFY_REFUND");
        counter("recon_dispute", "check", "unknown");
        counter("recon_run", "status", "pass");
        counter("recon_run", "status", "fail");
        counter("notify_dead");
        counter("notify_delivered");
    }

    /** payment.<domain>.<event>；事件名与域前缀重复时去重。 */
    static String metricName(String event) {
        String e = event;
        String prefix = domain + "_";
        if (e.startsWith(prefix)) {
            e = e.substring(prefix.length());
        }
        return "payment." + domain + "." + e;
    }

    private static String nz(String v) {
        return v == null || v.isBlank() ? "unknown" : v;
    }
}