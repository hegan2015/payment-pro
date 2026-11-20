package com.qoder.payment.channel.spi;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CH-2a 适配器注册表：按 channelCode 收编全部 {@link ChannelAdapter} Bean（可插拔）。
 *
 * <p>新增渠道 = 实现 SPI + 注册 Bean + 一条 channel_config，无需改 ChannelService/TCC 参与者。
 * 重复 channelCode 启动即失败（避免路由歧义）；未注册渠道受理时明确拒绝，不静默降级。
 */
@Slf4j
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();

    public ChannelAdapterRegistry(List<ChannelAdapter> beans) {
        for (ChannelAdapter adapter : beans) {
            String code = adapter.channelCode();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("ChannelAdapter.channelCode 不可为空: " + adapter.getClass().getName());
            }
            ChannelAdapter previous = adapters.put(code, adapter);
            if (previous != null) {
                throw new IllegalStateException("渠道适配器 channelCode 重复: " + code);
            }
        }
        log.info("CH-2a 渠道 SPI 注册表就绪 adapters={}", adapters.keySet());
    }

    public ChannelAdapter find(String channelCode) {
        return channelCode == null ? null : adapters.get(channelCode);
    }

    public ChannelAdapter resolve(String channelCode) {
        ChannelAdapter adapter = find(channelCode);
        if (adapter == null) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道适配器未注册: " + channelCode);
        }
        return adapter;
    }

    public List<String> codes() {
        return new ArrayList<>(adapters.keySet());
    }

    public List<ChannelAdapter> all() {
        return Collections.unmodifiableList(new ArrayList<>(adapters.values()));
    }

    public boolean contains(String channelCode) {
        return adapters.containsKey(channelCode);
    }
}