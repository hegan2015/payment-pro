package com.qoder.payment.payment.config;

import com.qoder.payment.common.db.FundShardEndpointSupport;
import com.qoder.payment.common.db.ShardProbeSupport;
import com.qoder.payment.common.db.ShardRouteState;
import com.qoder.payment.common.db.ShardRowMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.payment.mapper.PaymentMapper;
import com.qoder.payment.payment.mapper.RefundMapper;
import com.qoder.payment.payment.mapper.RequestIndexMapper;
import com.qoder.payment.payment.service.PaymentRequestIndex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * I-5b 支付域分片装配（<b>仅 docker + payment.sharding.enabled=true</b>）。
 *
 * <p>local（H2 单库单表）一个 Bean 都不加载：PaymentService 经 {@code ObjectProvider} 取不到闸门/镜像，
 * 走改造前原路径（序列取号 + uk_payment_request 全局唯一键），行为逐字节不变。
 *
 * <p><b>分片键</b>：payment 按 {@code id}（即 paymentId）、refund_record 按 {@code payment_id}。
 * <b>不分片</b>：dtx_transaction / dtx_branch / outbox（协调器终态 + 事务 outbox 原子性）、
 * idempotent_record / sequences / shedlock / amount_review、以及新增的
 * payment_request_index / refund_request_index / shard_route_state（跨片全局幂等与路由状态）。
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.sharding", name = "enabled", havingValue = "true")
public class PaymentShardConfig {

    public static final String DOMAIN = "payment-payment";

    /** 资金域分片表规格（逻辑表 / 主键 / 分片键）。 */
    public static List<ShardRowMirror.Spec> specs() {
        return List.of(new ShardRowMirror.Spec("payment", "id", "id"),
                new ShardRowMirror.Spec("refund_record", "id", "payment_id"));
    }

    @Bean
    public ShardRouteState paymentShardRouteState(@Qualifier("shardingSingleDataSource") DataSource raw) {
        return new ShardRouteState(raw, DOMAIN);
    }

    @Bean
    public PaymentShardBootstrap paymentShardBootstrap(ShardRouteState paymentShardRouteState) {
        return new PaymentShardBootstrap(paymentShardRouteState, specs());
    }

    @Bean
    public ShardRowMirror paymentShardRowMirror(@Qualifier("shardingSingleDataSource") DataSource raw,
                                                DataSource routed, ShardRuntime runtime) {
        return new ShardRowMirror(raw, routed, runtime, specs());
    }

    @Bean
    public FundShardEndpointSupport paymentFundShardEndpointSupport(
            @Qualifier("shardingSingleDataSource") DataSource raw,
            ShardProbeSupport probe, ShardRowMirror paymentShardRowMirror,
            ShardRouteState paymentShardRouteState, ShardRuntime runtime) {
        return new FundShardEndpointSupport(DOMAIN, probe, paymentShardRowMirror, paymentShardRouteState,
                () -> PaymentShardBootstrap.backfill(raw, runtime.shardCount()));
    }

    @Bean
    public PaymentRequestIndex paymentRequestIndex(RequestIndexMapper indexMapper, PaymentMapper paymentMapper,
                                                   RefundMapper refundMapper, ShardRuntime runtime,
                                                   ShardRowMirror paymentShardRowMirror,
                                                   PlatformTransactionManager txManager) {
        return new PaymentRequestIndex(indexMapper, paymentMapper, refundMapper, runtime,
                paymentShardRowMirror, txManager);
    }
}