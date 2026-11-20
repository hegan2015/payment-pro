package com.qoder.payment.channel.spi;

import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import org.springframework.stereotype.Component;

/**
 * CH-2a SPI 可插拔参考实现（「空适配器」）：证明新增渠道只需实现 SPI + 注册 Bean，
 * 无需改动 ChannelService / TCC 参与者 / 路由（开闭原则）。
 *
 * <p>出厂 channel_config.status=DISABLED → 永不接流量；运维显式启用后才参与路由。
 * 无外部副作用、无账单能力，仅作 SPI 契约最小样例与注册表可插拔断言靶。
 */
@Component
public class EchoChannelAdapter implements ChannelAdapter {

    public static final String CODE = "ECHO_BANK";

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public String adapterType() {
        return "ECHO";
    }

    @Override
    public String env() {
        return "SANDBOX";
    }

    @Override
    public ChannelResult pay(ChannelPayRequest request) {
        return ChannelResult.success("ECHO-" + request.getBizNo());
    }

    @Override
    public ChannelResult refund(ChannelRefundRequest request) {
        return ChannelResult.success("ECHO-R-" + request.getBizNo());
    }

    @Override
    public ChannelResult query(String bizNo) {
        ChannelResult r = new ChannelResult();
        r.setStatus(ChannelResult.FAILED);
        r.setChannelStatus("NOT_FOUND");
        r.setReason("ECHO 参考实现不留渠道侧账本: " + bizNo);
        return r;
    }

    @Override
    public ChannelResult close(String bizNo) {
        return ChannelResult.success(null);
    }
}