package com.qoder.payment.notify.service;

import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import com.qoder.payment.notify.mapper.NotifyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 商户通知推送：HTTP POST JSON，2xx 即送达；
 * 失败按 {5,10,20,40,80}s 退避重试，最多 5 次置 FAILED（商户可 PULL 补查）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPusher {

    private static final long[] BACKOFF_SECONDS = {5, 10, 20, 40, 80};
    private static final int MAX_ATTEMPTS = 5;

    private final NotifyRecordMapper notifyRecordMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Scheduled(fixedDelay = 3000)
    public void push() {
        List<NotifyRecordDO> pending = notifyRecordMapper.selectPending(System.currentTimeMillis(), 50);
        for (NotifyRecordDO record : pending) {
            long attempts = record.getAttempts() + 1;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(record.getCallbackUrl()))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(record.getPayload()))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    notifyRecordMapper.updateDelivery(record.getId(), "DELIVERED", attempts, 0, null);
                    log.info("商户通知送达 notifyId={} url={}", record.getId(), record.getCallbackUrl());
                } else {
                    retryOrFail(record, attempts, "HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                retryOrFail(record, attempts, describe(e));
            }
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void retryOrFail(NotifyRecordDO record, long attempts, String error) {
        if (attempts >= MAX_ATTEMPTS) {
            notifyRecordMapper.updateDelivery(record.getId(), "FAILED", attempts, 0, error);
            log.warn("商户通知重试耗尽 notifyId={}: {}", record.getId(), error);
        } else {
            long delay = BACKOFF_SECONDS[(int) Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
            notifyRecordMapper.updateDelivery(record.getId(), "PENDING", attempts, System.currentTimeMillis() + delay, error);
            log.warn("通知投递重试 notifyId={} attempts={} error={}", record.getId(), attempts, error);
        }
    }
}