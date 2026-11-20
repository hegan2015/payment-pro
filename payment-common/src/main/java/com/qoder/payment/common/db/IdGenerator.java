package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntSupplier;

/**
 * I-5a 全局发号器（雪花算法，解决 §0.5 E-7「每片独立 sequences 会重号」）。
 *
 * <p>位布局：{@code 1 符号位 | 41 毫秒时间戳(相对 epoch) | 10 workerId | 12 序列号}，
 * 单实例单毫秒 4096 个、1024 个实例互不重号。{@link #next(String)} 返回「前缀 + 雪花十进制」，
 * 保留既有 ID 可读性（结算 {@code S...}、对账报告 {@code RC...}），长度 ≤ 21 < VARCHAR(32)。
 *
 * <p><b>只在分片开启时被调用</b>：{@code payment.sharding.enabled=false}（local 默认）时
 * settle/recon 仍走 {@code next_value_for('seq_x')}，行为逐字节不变，本类不产生任何连接/取号。
 * workerId 首次取号时才向 ZK 申请（惰性），未启用分片的进程零副作用。
 *
 * <p>时钟回拨保护：小幅回拨（≤ 2ms）自旋等待；大幅回拨改用「逻辑时钟」（沿用 lastTimestamp 继续发号），
 * 宁可牺牲时间单调也绝不重号；序列号耗尽则等待下一毫秒。
 */
public class IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(IdGenerator.class);

    /** 2024-01-01T00:00:00Z：固定 epoch，41 位可用约 69 年。 */
    public static final long DEFAULT_EPOCH = 1704067200000L;

    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long epoch;
    private final IntSupplier workerIdSupplier;

    private volatile int workerId = -1;
    private volatile String workerIdSource = "UNRESOLVED";
    private long lastTimestamp = -1L;
    private long sequence;
    private long generated;
    private boolean clockWarned;

    public IdGenerator(IntSupplier workerIdSupplier) {
        this(DEFAULT_EPOCH, workerIdSupplier);
    }

    public IdGenerator(long epoch, IntSupplier workerIdSupplier) {
        this.epoch = epoch;
        this.workerIdSupplier = workerIdSupplier;
    }

    /** 前缀 + 雪花 ID（可读性保留）。 */
    public String next(String prefix) {
        return (prefix == null ? "" : prefix) + nextLong();
    }

    public synchronized long nextLong() {
        int worker = workerId();
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            long offset = lastTimestamp - now;
            if (offset <= 2L) {
                now = waitUntil(lastTimestamp);
            } else {
                if (!clockWarned) {
                    log.error("I-5a 全局发号检测到时钟回拨 {}ms，改用逻辑时钟继续发号（绝不重号）", offset);
                    clockWarned = true;
                }
                now = lastTimestamp;
            }
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        generated++;
        return ((now - epoch) << TIMESTAMP_SHIFT) | ((long) worker << WORKER_SHIFT) | sequence;
    }

    private static long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    /** 惰性分配 workerId（只在首次取号时连 ZK）。 */
    public int workerId() {
        int cached = workerId;
        if (cached >= 0) {
            return cached;
        }
        synchronized (this) {
            if (workerId < 0) {
                int assigned = workerIdSupplier.getAsInt();
                if (assigned < 0 || assigned > MAX_WORKER_ID) {
                    throw new IllegalStateException("workerId 越界（0.." + MAX_WORKER_ID + "）: " + assigned);
                }
                workerId = assigned;
            }
            return workerId;
        }
    }

    public String workerIdSource() {
        workerId();
        return workerIdSource;
    }

    public void workerIdSource(String source) {
        this.workerIdSource = source;
    }

    public synchronized long generatedCount() {
        return generated;
    }

    public long epoch() {
        return epoch;
    }

    /** 从雪花 ID 反解 workerId（供 verify-sharding.py 断言多实例 workerId 互不相同）。 */
    public static int workerIdOf(long id) {
        return (int) ((id >> WORKER_SHIFT) & MAX_WORKER_ID);
    }
}