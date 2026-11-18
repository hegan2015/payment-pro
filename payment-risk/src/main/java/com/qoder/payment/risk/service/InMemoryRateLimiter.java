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
}