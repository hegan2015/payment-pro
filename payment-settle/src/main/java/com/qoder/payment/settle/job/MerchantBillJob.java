package com.qoder.payment.settle.job;

import com.qoder.payment.common.job.JobHandler;
import com.qoder.payment.settle.bill.MerchantBillService;
import com.qoder.payment.settle.mapper.SettleAggregateMapper;
import com.qoder.payment.settle.service.SettleSupport;
import com.qoder.payment.common.db.ShardKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * ST-5 日账单生成作业（JobHandler 名 {@code settleBill}）：日切后为昨日有流水的商户出 DAILY 账单。
 * 逐片 ShedLock + 按 merchant 取模分片（不重不漏）；账单幂等键保证重复触发不重不改。
 *
 * <p>param：业务日 epoch millis（可空=昨日）；{@code MONTHLY:<millis>} 生成月账单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantBillJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    private final MerchantBillService billService;
    private final SettleAggregateMapper aggregateMapper;
    private final SettleSupport support;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "settleBill";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        String raw = param == null ? "" : param.trim();
        String type = MerchantBillService.DAILY;
        if (raw.toUpperCase().startsWith("MONTHLY")) {
            type = MerchantBillService.MONTHLY;
            raw = raw.replaceFirst("^MONTHLY:?", "");
        }
        long day = raw.isBlank() ? SettleSupport.today() - SettleSupport.DAY_MS : Long.parseLong(raw);
        long from = SettleSupport.dayStart(day);
        long to = from + SettleSupport.DAY_MS;
        final String billType = type;
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            run(index, total, from, to, billType);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration config = new LockConfiguration(ClockProvider.now(),
                name() + ":" + index + "/" + total, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> run(index, total, from, to, billType), config);
    }

    private void run(int index, int total, long from, long to, String billType) {
        List<Map<String, Object>> rows = support.readYourWrite(() -> aggregateMapper.merchantDays(from, to));
        TreeSet<String> merchants = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            Object merchant = row.get("merchant_id");
            if (merchant != null) {
                merchants.add(String.valueOf(merchant));
            }
        }
        int generated = 0;
        for (String merchantId : merchants) {
            if (ShardKeys.shardOf(merchantId, total) != index) {
                continue;
            }
            billService.generate(merchantId, from, billType);
            generated++;
        }
        log.info("ST-5 账单跑批 shard={}/{} bizDate={} type={} merchants={} generated={}",
                index, total, SettleSupport.dayText(from), billType, merchants.size(), generated);
    }
}