package com.qoder.payment.settle.config;

import com.qoder.payment.common.db.ShardPreBootstrap;
import com.qoder.payment.common.db.ShardRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * ST-x 分片态前置建表（docker）：新表全部是 <b>SINGLE 单库表</b>，必须在 ShardingSphere
 * DataSource 创建之前存在于原单库，否则 SINGLE 规则元数据加载失败导致启动 fail-fast。
 *
 * <p>只依赖 {@code shardingSingleDataSource}（原单库），不依赖 {@code @Primary} 路由 DataSource
 * （那正是本钩子参与创建的 Bean，会成环）。local 未开启分片时本 Bean 不装配，零影响。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.sharding", name = "enabled", havingValue = "true")
public class SettleShardPreBootstrap implements ShardPreBootstrap {

    private final DataSource single;

    public SettleShardPreBootstrap(@Qualifier("shardingSingleDataSource") DataSource single) {
        this.single = single;
    }

    @Override
    public void preBootstrap(DataSource raw, ShardRuntime runtime) {
        log.info("ST-x 分片前置建表（单库不分片表）app={} shardingActive={}", runtime.appName(), runtime.isActive());
        SettleSchema.ensure(raw == null ? single : raw);
    }
}