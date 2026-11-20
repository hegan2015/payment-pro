package com.qoder.payment.channel.spi;

import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CH-2a 适配器装配：模拟银行主/备两个实例共用同一 SPI 实现（不同 channelCode），
 * 证明「新增渠道只需实现 SPI + 注册」；沙箱与 ECHO 参考实现各自 @Component 注册。
 *
 * <p>备用渠道 MOCK_BANK_B 与主渠道行为完全一致（同样的 0000 拒绝规则），
 * 出厂权重更低 → 默认路由仍选主渠道，同步路径行为不变；主渠道维护/熔断时自动切换。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChannelAdapterConfiguration {

    private final ChannelTradeMapper tradeMapper;
    private final ChannelFaultInjector faults;
    private final Environment env;

    @Bean
    public MockBankAdapter mockBankAdapter() {
        boolean async = Boolean.parseBoolean(env.getProperty("payment.channel.async", "false"));
        log.info("CH-2a 装配模拟银行主渠道 MOCK_BANK async={}", async);
        return new MockBankAdapter("MOCK_BANK", tradeMapper, faults, async);
    }

    @Bean
    public MockBankAdapter mockBankBackupAdapter() {
        boolean async = Boolean.parseBoolean(env.getProperty("payment.channel.async", "false"));
        log.info("CH-2a 装配模拟银行备用渠道 MOCK_BANK_B async={}", async);
        return new MockBankAdapter("MOCK_BANK_B", tradeMapper, faults, async);
    }
}