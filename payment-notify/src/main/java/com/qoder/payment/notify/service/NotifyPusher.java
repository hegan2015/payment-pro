package com.qoder.payment.notify.service;

import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeFacade;
import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import com.qoder.payment.notify.mapper.NotifyRecordMapper;
import com.qoder.tcc.TccContextUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 商户通知推送：推送前经 {@link NotifySigner} 签名，HTTP POST JSON 携带签名三要素头，2xx 即送达；
 * 投递失败按 {5,10,20,40,80}s 退避重试，最多 5 次置 FAILED（商户可 PULL 补查）。
 * <p>签名失败/密钥缺失 → 留 PENDING 退避（不丢）+ 告警日志，待密钥就绪后自动收敛。
 * <p>callbackUrl 支持 https：local 信任自签证书 {@code config/payment-pro-cert.pem}（若存在）；http 兼容（签名仍加）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPusher {

    private static final long[] BACKOFF_SECONDS = {5, 10, 20, 40, 80};
    private static final int MAX_ATTEMPTS = 5;

    private final NotifyRecordMapper notifyRecordMapper;
    private final NotifySigner notifySigner;

    /** N-2：死信差错单接入（recon 域 X-1），不可达时告警留痕、由死信兜底扫描补建。 */
    @DubboReference
    private DisputeFacade disputeFacade;

    @Value("${payment.notify.callback.cert-path:config/payment-pro-cert.pem}")
    private String certPath;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        this.httpClient = buildHttpClient();
    }

    /**
     * I-3：分布式锁保证多实例只有一个推送（锁名 notifyPush，与 XXL-Job JobHandler 同名）。
     * lockAtMostFor 覆盖最坏一轮（50 条 × 3s 超时 + 退避），进程猝死时锁自动到期不永久卡死
     * （另有 ShedLockOrphanRecovery 启动即回收本机已消失进程的孤儿锁）；
     * lockAtLeastFor=0 保证 3s 轮询兜底不被人为拖慢（outbox/通知投递不得停摆）。
     * 调度触发见 {@link NotifyPushJob}（原本地 @Scheduled(fixedDelay=3000) 语义不变）。
     */
    @SchedulerLock(name = "notifyPush", lockAtMostFor = "PT3M", lockAtLeastFor = "PT0S")
    public void push() {
        List<NotifyRecordDO> pending = notifyRecordMapper.selectPending(System.currentTimeMillis(), 50);
        for (NotifyRecordDO record : pending) {
            long attempts = record.getAttempts() + 1;
            // N-4 additive 护栏：PULL 记录被重发重置为 PENDING 但无 callbackUrl → 退回 PULL（商户补查），
            // 不做无地址推送（避免 NPE 噪声与无谓重试耗尽）。既有 PENDING 记录恒有 callbackUrl，行为不变。
            if (record.getCallbackUrl() == null || record.getCallbackUrl().isBlank()) {
                notifyRecordMapper.updateDelivery(record.getId(), "PULL", record.getAttempts(), 0,
                        "PULL: 无 callbackUrl，商户补查");
                continue;
            }
            NotifySigner.SignResult sign;
            try {
                sign = notifySigner.sign(record);
            } catch (NotifySigner.SignException e) {
                // 签名失败/密钥缺失：留 PENDING 退避不丢 + 告警（不置 FAILED，密钥就绪后自动收敛）。
                long delay = BACKOFF_SECONDS[(int) Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
                notifyRecordMapper.updateDelivery(record.getId(), "PENDING", attempts, System.currentTimeMillis() + delay, "SIGN: " + e.getMessage());
                log.error("[告警] 通知签名失败留 PENDING notifyId={} attempts={}: {}", record.getId(), attempts, e.getMessage());
                continue;
            }
            notifyRecordMapper.updateSignature(record.getId(), sign.signType(), sign.signature(), sign.nonce());
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(record.getCallbackUrl()))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .header("X-Payment-Signature", sign.signature())
                        .header("X-Payment-Timestamp", sign.timestamp())
                        .header("X-Payment-Nonce", sign.nonce())
                        .header("X-Payment-Sign-Type", sign.signType())
                        .POST(HttpRequest.BodyPublishers.ofString(record.getPayload()))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    notifyRecordMapper.updateDelivery(record.getId(), "DELIVERED", attempts, 0, null);
                    log.info("商户通知送达 notifyId={} url={} signType={}", record.getId(), record.getCallbackUrl(), sign.signType());
                    MetricsSupport.notifyDelivered();
                    resolveDisputeQuietly(record.getId(), "通知送达（含死信重放）notifyId=" + record.getId());
                } else {
                    retryOrFail(record, attempts, "HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                retryOrFail(record, attempts, describe(e));
            }
        }
    }

    /** 构建 HttpClient：存在本地自签证书则信任之（https 回调），否则用默认信任（兼容 http/公网 https）。 */
    private HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        Path cert = Path.of(certPath);
        if (Files.exists(cert)) {
            try (InputStream in = Files.newInputStream(cert)) {
                X509Certificate trusted = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setCertificateEntry("payment-pro-local", trusted);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keyStore);
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), null);
                builder.sslContext(sslContext);
                log.info("通知回调信任本地自签证书 path={}", certPath);
            } catch (Exception e) {
                log.warn("加载回调自签证书失败 path={}，回退默认信任: {}", certPath, e.getMessage());
            }
        }
        return builder.build();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void retryOrFail(NotifyRecordDO record, long attempts, String error) {
        if (attempts >= MAX_ATTEMPTS) {
            notifyRecordMapper.updateDelivery(record.getId(), "FAILED", attempts, 0, error);
            // N-2 死信告警：ERROR 日志 + payment_notify_dead_total 递增 + NOTIFY_DEAD 差错单（幂等 by notifyId）
            log.error("[告警] 商户通知重试耗尽置死信 notifyId={} bizId={} attempts={} url={} lastError={}",
                    record.getId(), record.getBizId(), attempts, maskUrl(record.getCallbackUrl()), error);
            MetricsSupport.notifyDead();
            createDeadDisputeQuietly(record, error);
        } else {
            long delay = BACKOFF_SECONDS[(int) Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
            notifyRecordMapper.updateDelivery(record.getId(), "PENDING", attempts, System.currentTimeMillis() + delay, error);
            log.warn("通知投递重试 notifyId={} attempts={} error={}", record.getId(), attempts, error);
        }
    }

    /**
     * N-2 死信兜底扫描：FAILED 记录幂等补建 NOTIFY_DEAD 差错单
     * （覆盖耗尽瞬间 recon 域不可达的场景；重复扫描不重复建单）。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void deadSweep() {
        try {
            for (NotifyRecordDO record : notifyRecordMapper.selectFailed(50)) {
                createDeadDisputeQuietly(record, record.getLastError());
            }
        } catch (Exception e) {
            log.warn("死信兜底扫描异常: {}", e.getMessage());
        }
    }

    private void createDeadDisputeQuietly(NotifyRecordDO record, String error) {
        try {
            DisputeCreateRequest request = new DisputeCreateRequest();
            request.setType("NOTIFY_DEAD");
            request.setSource("NOTIFY");
            request.setBizNo(record.getId());
            request.setPaymentId(record.getBizId());
            request.setAmount(payloadAmount(record));
            request.setStrategy("NOTIFY_REPLAY");
            request.setReason("通知重试耗尽 attempts=" + record.getAttempts()
                    + " url=" + maskUrl(record.getCallbackUrl()) + " lastError=" + error);
            disputeFacade.create(request);
        } catch (Exception e) {
            log.error("[告警] 死信差错单接入失败（兜底扫描将补建） notifyId={}: {}", record.getId(), e.getMessage());
        }
    }

    private void resolveDisputeQuietly(String notifyId, String detail) {
        try {
            disputeFacade.resolveBySourceBiz("NOTIFY", notifyId, "notify-pusher", detail);
        } catch (Exception e) {
            log.warn("死信差错单自动核销失败（不影响送达） notifyId={}: {}", notifyId, e.getMessage());
        }
    }

    private static long payloadAmount(NotifyRecordDO record) {
        try {
            Map<?, ?> payload = TccContextUtil.fromJson(record.getPayload(), Map.class);
            if (payload == null) {
                return 0L;
            }
            Object amount = payload.get("amount");
            // N-3 新事件统一 envelope：金额在 payload.payload 内（冻结事件仍是顶层 amount，口径不变）
            if (amount == null && payload.get("payload") instanceof Map<?, ?> inner) {
                amount = inner.get("amount");
            }
            return amount instanceof Number n ? n.longValue() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 告警脱敏：callbackUrl 只留 scheme+host+path，去查询串。 */
    private static String maskUrl(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}