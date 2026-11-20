package com.qoder.payment.common.db;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * I-5a 分片键归属算法（跨 JVM 确定性）：CRC32(分片键) % 分片数。
 *
 * <p>与 {@code ReconService#shardOf}（I-3 分片对账）同一算法族，保证「Java 侧建表/影子复制/探针」
 * 与「ShardingSphere 侧路由算法」对同一行给出同一分片，杜绝双写错位。
 *
 * <p>不引入任何中间件依赖，local 也可安全加载（纯函数）。
 */
public final class ShardKeys {

    /** 分片表后缀分隔符：逻辑表 settle_record → 物理表 settle_record_0..3。 */
    public static final String SUFFIX = "_";

    public static int shardOf(String shardKey, int shardCount) {
        if (shardCount <= 1) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update((shardKey == null ? "" : shardKey).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % shardCount);
    }

    public static String physicalTable(String logicTable, int shardIndex) {
        return logicTable + SUFFIX + shardIndex;
    }

    private ShardKeys() {
    }
}