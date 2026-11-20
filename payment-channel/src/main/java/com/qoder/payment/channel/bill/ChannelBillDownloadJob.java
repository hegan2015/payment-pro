package com.qoder.payment.channel.bill;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.service.ChannelConfigService;
import com.qoder.payment.channel.service.ChannelLimitService;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.job.JobHandler;
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

/**
 * CH-4 日切账单下载作业（I-3 JobHandler 名 {@code channelBillDownload}，XXL-Job / 运维端点触发）。
 *
 * <p>param 形如 {@code MOCK_BANK:20261120}（单渠道单日）或空（全部启用且支持账单的渠道，当日）。
 * 逐渠道加 ShedLock（锁名 {@code channelBillDownload:<channelCode>}），多实例互斥、进程猝死自动到期。
 *
 * <p><b>不新增本地周期调度</b>（与 reconRun 同口径）：避免凭空产生账单/差错单；
 * 下载失败由作业结果留痕 + 差错单（SOURCE_UNAVAILABLE）兜底，可重复触发（幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelBillDownloadJob implements JobHandler {

    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    private final ChannelBillService billService;
    private final ChannelConfigService configService;
    private final ChannelAdapterRegistry registry;
    private final DynamicConfig config;
    private final ObjectProvider<LockProvider> lockProvider;

    @Override
    public String name() {
        return "channelBillDownload";
    }

    @Override
    public void execute(int shardIndex, int shardTotal, String param) {
        if (!config.getBool(ChannelConfigKeys.BILL_ENABLED, ChannelConfigKeys.DEFAULT_BILL_ENABLED)) {
            log.info("渠道账单下载作业已关闭（channel.bill.enabled=false）");
            return;
        }
        String channelCode = null;
        String bizDate = null;
        if (param != null && !param.isBlank()) {
            String[] parts = param.trim().split(":");
            channelCode = parts[0].trim();
            if (parts.length > 1 && !parts[1].isBlank()) {
                bizDate = parts[1].trim();
            }
        }
        String day = bizDate == null ? ChannelLimitService.bizDate(System.currentTimeMillis()) : bizDate;
        int total = Math.max(1, shardTotal);
        int index = Math.max(0, Math.min(shardIndex, total - 1));
        int i = 0;
        for (ChannelConfigDO c : configService.list()) {
            if (channelCode != null && !channelCode.equals(c.getChannelCode())) {
                continue;
            }
            if (!"ENABLED".equalsIgnoreCase(c.getStatus())) {
                continue;
            }
            ChannelAdapter adapter = registry.find(c.getChannelCode());
            if (adapter == null || !adapter.billSupported()) {
                continue;
            }
            if (i++ % total != index) {
                continue;
            }
            runLocked(c.getChannelCode(), day);
        }
    }

    private void runLocked(String channelCode, String day) {
        LockProvider provider = lockProvider.getIfAvailable();
        if (provider == null) {
            billService.download(channelCode, day);
            return;
        }
        LockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(),
                name() + ":" + channelCode, LOCK_AT_MOST, Duration.ZERO);
        executor.executeWithLock((Runnable) () -> billService.download(channelCode, day), lock);
    }
}