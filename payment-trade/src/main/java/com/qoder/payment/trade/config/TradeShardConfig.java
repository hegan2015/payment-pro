package com.qoder.payment.trade.config;

import com.qoder.payment.common.db.FundShardEndpointSupport;
import com.qoder.payment.common.db.FundShardPreBootstrap;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.common.db.ShardRouteState;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * I-5b 交易域分片装配（<b>仅 docker + payment.sharding.enabled=true</b>）。
 *
 * <p><b>分片键 = merchant_id</b>：{@code trade_order} 的幂等唯一键是
 * {@code (merchant_id, out_trade_no)}，分片键正是其首列 ⇒ <b>同商户同 outTradeNo 必落同片</b>，
 * 片内唯一键即等价于全局唯一键，§0.7「trade merchant+outTradeNo 唯一幂等不变」天然成立，
 * 无需额外索引表（与 payment 的 request_id 跨片问题不同）。
 *
 * <p><b>不分片</b>：{@code tcc_branch_log}（参与者分支幂等，须与订单写同库同事务）、
 * {@code idempotent_record}（REFUND_MARK 等幂等占位）、{@code sequences}、{@code shard_route_state}。
 *
 * <p>local（H2 单库单表）一个 Bean 都不加载，行为逐字节不变。
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.sharding", name = "enabled", havingValue = "true")
public class TradeShardConfig {

    public static final String DOMAIN = "payment-trade";

    public static List<ShardRowMirror.Spec> specs() {
        return List.of(new ShardRowMirror.Spec("trade_order", "id", "merchant_id"));
    }

    @Bean
    public ShardRouteState tradeShardRouteState(@Qualifier("shardingSingleDataSource") DataSource raw) {
        return new ShardRouteState(raw, DOMAIN);
    }

    @Bean
    public FundShardPreBootstrap tradeShardPreBootstrap(ShardRouteState tradeShardRouteState) {
        return new FundShardPreBootstrap(tradeShardRouteState, specs());
    }

    @Bean
    public ShardRowMirror tradeShardRowMirror(@Qualifier("shardingSingleDataSource") DataSource raw,
                                              DataSource routed, ShardRuntime runtime) {
        return new ShardRowMirror(raw, routed, runtime, specs());
    }

    @Bean
    public FundShardEndpointSupport tradeFundShardEndpointSupport(
            ShardProbeSupport probe, ShardRowMirror tradeShardRowMirror, ShardRouteState tradeShardRouteState) {
        return new FundShardEndpointSupport(DOMAIN, probe, tradeShardRowMirror, tradeShardRouteState, null);
    }
}