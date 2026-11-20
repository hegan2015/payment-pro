package com.qoder.payment.risk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内存滑动窗口（本地零依赖模式）；重启清零、不跨实例共享。 */
@Component
@ConditionalOnProperty(prefix = "payment.risk.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String payer, long now, long windowMillis, int max) {
        Deque<Long> window = windows.computeIfAbsent(payer, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= max) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** R-1/R-4 只读观测：裁剪过期点后返回窗口内笔数，不入队（不消耗额度）。 */
    @Override
    public long countInWindow(String payer, long now, long windowMillis) {
        Deque<Long> window = windows.get(payer);
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > windowMillis) {
                window.pollFirst();
            }
            return window.size();
        }
    }
}