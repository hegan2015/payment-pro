package com.qoder.payment.common.db;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * I-5a 标准分片算法：CRC32(分片键) % sharding-count，物理表 {@code <逻辑表>_<0..count-1>}。
 *
 * <p>用 ShardingSphere 官方扩展位 {@code CLASS_BASED / strategy=STANDARD} 装配（不自研薄循环）：
 * <ul>
 *   <li>{@code settle_record} 按 {@code merchant_id} 分 4 片 → {@code records(merchantId)} 精确单片路由；</li>
 *   <li>{@code recon_report} 按 {@code id} 哈希分 4 片 → 跨片 ORDER BY/LIMIT 由归并引擎处理。</li>
 * </ul>
 *
 * <p>范围查询（BETWEEN）无法定位单片 → 返回全部片，由归并引擎去重排序，绝不漏片。
 * 与 {@link ShardKeys} 共用同一函数，Java 侧复制/探针与 SQL 侧路由结果逐行一致。
 */
public final class Crc32ModShardingAlgorithm implements StandardShardingAlgorithm<String> {

    private static final String COUNT_KEY = "sharding-count";

    private int shardCount = 4;

    @Override
    public void init(Properties props) {
        if (props != null && props.getProperty(COUNT_KEY) != null) {
            shardCount = Integer.parseInt(props.getProperty(COUNT_KEY).trim());
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("sharding-count 必须 >= 1，实际 " + shardCount);
        }
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<String> shardingValue) {
        String target = ShardKeys.physicalTable(shardingValue.getLogicTableName(),
                ShardKeys.shardOf(shardingValue.getValue(), shardCount));
        for (String each : availableTargetNames) {
            if (each.equalsIgnoreCase(target)) {
                return each;
            }
        }
        throw new IllegalStateException("分片目标表不存在: " + target + "，可用 " + availableTargetNames);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<String> shardingValue) {
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "PP_CRC32_MOD";
    }
}