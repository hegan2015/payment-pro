package com.qoder.payment.payment.metrics;

import com.qoder.payment.common.metrics.MetricsSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * I-8 TCC DEAD 积压指标（纯追加埋点，不改协调器逻辑）。
 *
 * <p>DEAD 置位在 tcc-core {@code TccCoordinator#bumpRetryIfPending}（组件禁改、无 DEAD 回调），
 * 故在协调器宿主域以只读 gauge 暴露当前积压：{@code payment_payment_tcc_dead_backlog}
 * （SELECT COUNT dtx_transaction status='DEAD'，Prometheus 抓取时求值）。
 * 告警口径：gauge &gt; 0 即有资金事务待人工/对账介入；查询异常保留上次值，不影响业务。
 * 计数器 {@code payment_payment_tcc_dead_total} 由 MetricsSupport catalog 预注册（0 值基线）；
 * gauge 用 _backlog 后缀区分（Prometheus collector 按基名注册，同名 counter/gauge 冲突）。
 */
@Component
public class PaymentTccMetrics {

    public PaymentTccMetrics(JdbcTemplate jdbc) {
        AtomicLong last = new AtomicLong(0);
        MetricsSupport.gauge("tcc_dead_backlog", () -> {
            try {
                Long n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dtx_transaction WHERE status='DEAD'", Long.class);
                last.set(n == null ? 0L : n);
            } catch (Exception e) {
                // 库不可达/表缺失：保留上次值，指标采集绝不反噬业务
            }
            return last.get();
        });
    }
}