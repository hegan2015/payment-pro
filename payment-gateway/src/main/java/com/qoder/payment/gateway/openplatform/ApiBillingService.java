package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.facade.openplatform.ApiCallDelta;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * G-4a 调用计费埋点（<b>异步批量</b>，绝不阻塞/影响资金链路）。
 *
 * <p>请求线程只做内存累加（ConcurrentHashMap，纳秒级），后台单线程每 1s 批量上报
 * {@code OpenPlatformFacade.recordCalls} → member 库 openapi_call_log 计数 × 单价。
 *
 * <p><b>资金隔离红线</b>：本服务只依赖 OpenPlatformFacade（member 域开放平台表），
 * <b>不依赖</b> accounting/payment/trade 任何 facade —— 结构上不可能产生分录/支付单/余额变动。
 * 上报失败只记 WARN 并丢弃该批计量（计费可容忍丢失，资金不可受阻）。
 */
@Slf4j
@Component
public class ApiBillingService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    /** 单价按 appId 保存（拒绝计数不得覆盖单价，否则账单金额被清零）。 */
    private final Map<String, Long> prices = new ConcurrentHashMap<>();
    private ScheduledExecutorService flusher;

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    @PostConstruct
    public void start() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "openapi-billing-flush");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushQuietly, 1000, 1000, TimeUnit.MILLISECONDS);
        log.info("开放平台计费埋点已启动（异步 1s 批量上报，只写 openapi_call_log，不触碰资金账）");
    }

    @PreDestroy
    public void stop() {
        flushQuietly();
        if (flusher != null) {
            flusher.shutdownNow();
        }
    }

    /** 受理计数（验签/授权/配额通过后）。unitPrice 单位：分/次，0=免费。 */
    public void accept(String appId, String api, long unitPrice) {
        prices.put(appId, unitPrice);
        counter(appId, api).calls.incrementAndGet();
    }

    /** 拒绝计数（撤销/scope/限流/配额/验签失败），不计费、不改单价。 */
    public void reject(String appId, String api) {
        counter(appId, api).rejects.incrementAndGet();
    }

    /** 立即上报（控制台查账单前调用，保证读数即时；失败不抛）。 */
    public void flushQuietly() {
        try {
            flush();
        } catch (Exception | LinkageError t) {
            log.warn("开放平台计费上报失败（丢弃该批计量，不影响业务）: {}", t.toString());
        }
    }

    private void flush() {
        if (counters.isEmpty()) {
            return;
        }
        List<ApiCallDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            Counter counter = entry.getValue();
            long calls = counter.calls.getAndSet(0);
            long rejects = counter.rejects.getAndSet(0);
            if (calls == 0 && rejects == 0) {
                continue;
            }
            String[] parts = entry.getKey().split("\\|", 3);
            deltas.add(ApiCallDelta.of(parts[0], parts[1], parts[2], calls, rejects,
                    prices.getOrDefault(parts[0], 0L)));
        }
        if (deltas.isEmpty()) {
            return;
        }
        openPlatformFacade.recordCalls(deltas).orThrow();
    }

    private Counter counter(String appId, String api) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE);
        String key = appId + "|" + api + "|" + date;
        return counters.computeIfAbsent(key, k -> new Counter());
    }

    private static final class Counter {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong rejects = new AtomicLong();
    }
}