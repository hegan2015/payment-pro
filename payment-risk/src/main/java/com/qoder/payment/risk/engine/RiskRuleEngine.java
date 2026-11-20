package com.qoder.payment.risk.engine;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.risk.RiskRuleDTO;
import com.qoder.payment.risk.config.RiskKeys;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.InstructionSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R-1 QLExpress 规则执行器（引擎扩展位：自定义函数 + 指令集缓存 + 原生超时）。
 *
 * <p><b>§5.4 误配置护栏（最高优先级，红线）</b>：
 * <ul>
 *   <li><b>编译失败</b> → 该规则标记 COMPILE_FAILED，<b>跳过（放行）</b>并告警，绝不阻断正常交易；</li>
 *   <li><b>执行异常</b>（未知函数/除零/类型错误） → 视为未命中（放行）+ 告警；</li>
 *   <li><b>超时</b>（引擎原生 timeout + 线程级硬超时双保险） → 视为未命中（放行）+ 告警；</li>
 *   <li>以上任一降级都<b>不影响内置保守规则</b>（单笔限额 + 滑动窗口限频仍照常执行），
 *       故既不因规则错误误杀正常交易，也不静默放行高风险。</li>
 * </ul>
 */
@Slf4j
@Component
public class RiskRuleEngine {

    /** 编译结果。 */
    public record Compile(boolean ok, String error) {
    }

    /** 执行结果：hit=命中；degraded=因护栏降级（放行）；errorKind=COMPILE/EXEC/TIMEOUT。 */
    public record Eval(boolean hit, boolean degraded, String errorKind, String error) {
        static final Eval HIT = new Eval(true, false, null, null);
        static final Eval MISS = new Eval(false, false, null, null);
    }

    private final ExpressRunner runner = new ExpressRunner(true, false);
    private final RiskFunctions functions = new RiskFunctions();
    private final Map<String, Compile> compileCache = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "risk-rule-exec");
        t.setDaemon(true);
        return t;
    });
    private final DynamicConfig config;

    private final AtomicLong compileFailures = new AtomicLong();
    private final AtomicLong execErrors = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong evaluated = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();

    public RiskRuleEngine(DynamicConfig config) {
        this.config = config;
        register();
    }

    private void register() {
        try {
            runner.addFunctionOfServiceMethod("txnCount", functions, "txnCount",
                    new Class<?>[]{Object.class, Object.class}, null);
            runner.addFunctionOfServiceMethod("quotaUsed", functions, "quotaUsed",
                    new Class<?>[]{String.class, String.class}, null);
            runner.addFunctionOfServiceMethod("quotaCount", functions, "quotaCount",
                    new Class<?>[]{String.class, String.class}, null);
            runner.addFunctionOfServiceMethod("inList", functions, "inList",
                    new Class<?>[]{String.class, String.class, String.class}, null);
            runner.addFunctionOfServiceMethod("deviceRisk", functions, "deviceRisk",
                    new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("ipRisk", functions, "ipRisk",
                    new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("hourOfDay", functions, "hourOfDay", new Class<?>[]{}, null);
            runner.addFunctionOfServiceMethod("amountDeviation", functions, "amountDeviation", new Class<?>[]{}, null);
            // R-5 画像函数（扩展既有 deviceRisk/ipRisk 因子，不另起评分体系）
            runner.addFunctionOfServiceMethod("deviceAccountCount", functions, "deviceAccountCount",
                    new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("deviceStable", functions, "deviceStable",
                    new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("ipProxy", functions, "ipProxy", new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("ipIdc", functions, "ipIdc", new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("ipCountry", functions, "ipCountry", new Class<?>[]{String.class}, null);
            // R-6 AML 函数（同一引擎、同一护栏：编译失败/执行异常/超时一律放行并告警）
            runner.addFunctionOfServiceMethod("dayAmount", functions, "dayAmount", new Class<?>[]{Object.class}, null);
            runner.addFunctionOfServiceMethod("dayCount", functions, "dayCount", new Class<?>[]{Object.class}, null);
            runner.addFunctionOfServiceMethod("sanctionHit", functions, "sanctionHit",
                    new Class<?>[]{String.class}, null);
            runner.addFunctionOfServiceMethod("pepHit", functions, "pepHit", new Class<?>[]{String.class}, null);
            log.info("QLExpress 自定义函数注册完成：txnCount/quotaUsed/quotaCount/inList/deviceRisk/ipRisk/hourOfDay/"
                    + "amountDeviation/deviceAccountCount/deviceStable/ipProxy/ipIdc/ipCountry/"
                    + "dayAmount/dayCount/sanctionHit/pepHit");
        } catch (Exception e) {
            // 函数注册失败不影响启动：规则执行时未知函数 → 护栏放行 + 告警
            log.error("QLExpress 自定义函数注册失败（规则将走护栏放行）: {}", e.getMessage(), e);
        }
    }

    /** 编译校验（带缓存）：失败不抛异常，返回错误摘要供规则标记与告警。 */
    public Compile compile(String expr) {
        if (expr == null || expr.isBlank()) {
            return new Compile(false, "表达式为空");
        }
        return compileCache.computeIfAbsent(expr, e -> {
            try {
                InstructionSet is = runner.parseInstructionSet(e);
                if (is == null) {
                    return new Compile(false, "指令集为空");
                }
                return new Compile(true, null);
            } catch (Throwable t) {
                compileFailures.incrementAndGet();
                MetricsSupport.count("risk.rule.compile.fail");
                return new Compile(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    /** 规则集变更后清缓存（避免同名表达式旧编译结果残留）。 */
    public void clearCompileCache() {
        compileCache.clear();
    }

    public long timeoutMs() {
        long v = config.getLong(RiskKeys.RULE_TIMEOUT_MS, RiskKeys.DEFAULT_RULE_TIMEOUT_MS);
        return v > 0 ? v : RiskKeys.DEFAULT_RULE_TIMEOUT_MS;
    }

    public long hardTimeoutMs() {
        long v = config.getLong(RiskKeys.RULE_HARD_TIMEOUT_MS, RiskKeys.DEFAULT_RULE_HARD_TIMEOUT_MS);
        return v > 0 ? v : RiskKeys.DEFAULT_RULE_HARD_TIMEOUT_MS;
    }

    /**
     * 执行规则条件。任何失败路径都返回「未命中 + degraded」，调用方据此放行并告警。
     */
    public Eval evaluate(RiskRuleDTO rule, RiskContext ctx) {
        String expr = rule.getConditionExpr();
        Compile c = compile(expr);
        if (!c.ok()) {
            return new Eval(false, true, "COMPILE", c.error());
        }
        evaluated.incrementAndGet();
        long timeout = timeoutMs();
        long hard = Math.max(hardTimeoutMs(), timeout + 50);
        Future<Object> future = pool.submit(() -> {
            RiskFunctions.bind(ctx);
            try {
                List<String> errors = new ArrayList<>();
                return runner.execute(expr, ctx.variables(), errors, true, false, timeout);
            } finally {
                RiskFunctions.unbind();
            }
        });
        try {
            Object v = future.get(hard, TimeUnit.MILLISECONDS);
            boolean hit = truthy(v);
            if (hit) {
                hits.incrementAndGet();
                return Eval.HIT;
            }
            return Eval.MISS;
        } catch (TimeoutException te) {
            future.cancel(true);
            timeouts.incrementAndGet();
            MetricsSupport.count("risk.rule.timeout");
            return new Eval(false, true, "TIMEOUT", "规则执行超时 >" + hard + "ms");
        } catch (Throwable t) {
            future.cancel(true);
            execErrors.incrementAndGet();
            MetricsSupport.count("risk.rule.exec.error");
            Throwable cause = t.getCause() == null ? t : t.getCause();
            String msg = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            // 引擎原生超时以异常形式抛出时同样归类 TIMEOUT
            String kind = msg.toLowerCase().contains("timeout") || msg.contains("超时") ? "TIMEOUT" : "EXEC";
            if ("TIMEOUT".equals(kind)) {
                timeouts.incrementAndGet();
                MetricsSupport.count("risk.rule.timeout");
            }
            return new Eval(false, true, kind, msg);
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("engine", "QLExpress");
        m.put("timeoutMs", timeoutMs());
        m.put("hardTimeoutMs", hardTimeoutMs());
        m.put("compiledExpressions", compileCache.size());
        m.put("compileFailures", compileFailures.get());
        m.put("execErrors", execErrors.get());
        m.put("timeouts", timeouts.get());
        m.put("evaluated", evaluated.get());
        m.put("hits", hits.get());
        return m;
    }
}