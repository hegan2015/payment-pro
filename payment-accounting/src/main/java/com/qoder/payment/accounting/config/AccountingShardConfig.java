package com.qoder.payment.accounting.config;

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
 * I-5b 账务域分片装配（<b>仅 docker + payment.sharding.enabled=true</b>）。
 *
 * <p><b>分片键 = account_id</b>：{@code account_balance} 与 {@code ledger_entry} 同账户必落同片，
 * 于是「片内余额/冻结一致」「按 accountId 排序加锁防死锁」「单账户流水 seq 有序」全部在片内成立。
 *
 * <p><b>不分片</b>：{@code transfer_record}（凭证只追加、VoucherGuard 语义）、{@code tcc_branch_log}
 * （参与者分支幂等，须与业务写同库同事务）、{@code idempotent_record}（五层幂等占位）、
 * {@code sequences}、{@code reserve_check_log} / {@code reserve_bank_adjust}、{@code shard_route_state}。
 *
 * <p><b>跨片守恒</b>：单库分表方案下所有分片表与原单表同属一个 MySQL 库、同一连接，
 * 跨账户转账（多片）仍是<b>同一本地事务</b>，原子性与改造前一致；TCC 各参与者本地事务语义不变；
 * {@code trial()} 的 SUM/COUNT 由 ShardingSphere 归并引擎跨片汇总（ΣD=ΣC、Σbalance=0 全局成立）。
 *
 * <p>local（H2 单库单表）一个 Bean 都不加载，行为逐字节不变。
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.sharding", name = "enabled", havingValue = "true")
public class AccountingShardConfig {

    public static final String DOMAIN = "payment-accounting";

    public static List<ShardRowMirror.Spec> specs() {
        return List.of(new ShardRowMirror.Spec("account_balance", "account_id", "account_id"),
                new ShardRowMirror.Spec("ledger_entry", "id", "account_id"));
    }

    @Bean
    public ShardRouteState accountingShardRouteState(@Qualifier("shardingSingleDataSource") DataSource raw) {
        return new ShardRouteState(raw, DOMAIN);
    }

    @Bean
    public FundShardPreBootstrap accountingShardPreBootstrap(ShardRouteState accountingShardRouteState) {
        return new FundShardPreBootstrap(accountingShardRouteState, specs());
    }

    @Bean
    public ShardRowMirror accountingShardRowMirror(@Qualifier("shardingSingleDataSource") DataSource raw,
                                                   DataSource routed, ShardRuntime runtime) {
        return new ShardRowMirror(raw, routed, runtime, specs());
    }

    @Bean
    public FundShardEndpointSupport accountingFundShardEndpointSupport(
            ShardProbeSupport probe, ShardRowMirror accountingShardRowMirror,
            ShardRouteState accountingShardRouteState) {
        return new FundShardEndpointSupport(DOMAIN, probe, accountingShardRowMirror, accountingShardRouteState, null);
    }
}