package com.qoder.payment.channel.facade;

import com.qoder.payment.channel.bill.ChannelBillService;
import com.qoder.payment.channel.dataobject.ChannelConfigDO;
import com.qoder.payment.channel.route.ChannelMetricsCollector;
import com.qoder.payment.channel.route.ChannelRouter;
import com.qoder.payment.channel.service.ChannelConfigService;
import com.qoder.payment.channel.service.ChannelLimitService;
import com.qoder.payment.channel.service.ChannelQueryService;
import com.qoder.payment.channel.service.ChannelReceiptService;
import com.qoder.payment.channel.service.ChannelService;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.channel.ChannelAdapterDTO;
import com.qoder.payment.facade.channel.ChannelBillDTO;
import com.qoder.payment.facade.channel.ChannelConfigDTO;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelLimitDTO;
import com.qoder.payment.facade.channel.ChannelMetricsDTO;
import com.qoder.payment.facade.channel.ChannelReceiptRequest;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.payment.facade.channel.RouteResultDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 渠道网关 Dubbo 实现：CH-1 查询/回执 + CH-2a SPI 观测 + CH-3 路由 + CH-4 账单 + CH-5 限额/维护。
 * 全部经 {@link FacadeSupport#execute} 包装为统一 Result（错误码口径不变）。
 */
@DubboService
@RequiredArgsConstructor
public class ChannelFacadeImpl implements ChannelFacade {

    private final ChannelService channelService;
    private final ChannelQueryService channelQueryService;
    private final ChannelReceiptService channelReceiptService;
    private final ChannelConfigService channelConfigService;
    private final ChannelAdapterRegistry registry;
    private final ChannelRouter router;
    private final ChannelMetricsCollector metrics;
    private final ChannelLimitService limitService;
    private final ChannelBillService billService;

    @Override
    public Result<List<ChannelTradeDTO>> listTrades() {
        return FacadeSupport.execute(channelService::listTrades);
    }

    @Override
    public Result<ChannelTradeDTO> queryChannelTrade(String bizNo) {
        return FacadeSupport.execute(() -> channelQueryService.queryChannelTrade(bizNo));
    }

    @Override
    public Result<ChannelTradeDTO> receiveReceipt(ChannelReceiptRequest request) {
        return FacadeSupport.execute(() -> channelReceiptService.receiveReceipt(request));
    }

    @Override
    public Result<List<ChannelConfigDTO>> listChannels() {
        return FacadeSupport.execute(() -> {
            List<ChannelConfigDTO> out = new ArrayList<>();
            for (ChannelConfigDO c : channelConfigService.list()) {
                ChannelConfigDTO dto = new ChannelConfigDTO();
                BeanUtils.copyProperties(c, dto);
                out.add(dto);
            }
            return out;
        });
    }

    @Override
    public Result<List<ChannelAdapterDTO>> listAdapters() {
        return FacadeSupport.execute(() -> {
            List<ChannelAdapterDTO> out = new ArrayList<>();
            for (ChannelAdapter adapter : registry.all()) {
                ChannelAdapterDTO dto = new ChannelAdapterDTO();
                dto.setChannelCode(adapter.channelCode());
                dto.setAdapterClass(adapter.getClass().getSimpleName());
                dto.setAdapterType(adapter.adapterType());
                dto.setEnv(adapter.env());
                dto.setAsyncCapable(adapter.asyncCapable());
                dto.setBillSupported(adapter.billSupported());
                ChannelConfigDO c = channelConfigService.get(adapter.channelCode());
                dto.setConfigStatus(c == null ? "UNCONFIGURED" : c.getStatus());
                out.add(dto);
            }
            return out;
        });
    }

    @Override
    public Result<ChannelTradeDTO> queryChannelSide(String bizNo) {
        return FacadeSupport.execute(() -> channelQueryService.queryChannelSide(bizNo));
    }

    @Override
    public Result<RouteResultDTO> route(ChannelRouteRequest request) {
        return FacadeSupport.execute(() -> router.route(request));
    }

    @Override
    public Result<List<ChannelMetricsDTO>> channelMetrics() {
        return FacadeSupport.execute(() -> {
            List<ChannelMetricsDTO> out = new ArrayList<>();
            for (ChannelConfigDO c : channelConfigService.list()) {
                out.add(metrics.snapshot(c.getChannelCode()));
            }
            return out;
        });
    }

    @Override
    public Result<ChannelLimitDTO> getChannelLimit(String channelCode) {
        return FacadeSupport.execute(() -> limitService.view(channelCode));
    }

    @Override
    public Result<ChannelLimitDTO> setChannelLimit(String channelCode, Long singleLimit, Long dailyLimit) {
        return FacadeSupport.execute(() -> {
            channelConfigService.setLimits(channelCode, singleLimit, dailyLimit);
            return limitService.view(channelCode);
        });
    }

    @Override
    public Result<ChannelLimitDTO> setMaintenance(String channelCode, Long start, Long end) {
        return FacadeSupport.execute(() -> {
            channelConfigService.setMaintenance(channelCode, start, end);
            return limitService.view(channelCode);
        });
    }

    @Override
    public Result<ChannelBillDTO> downloadBill(String channelCode, String bizDate) {
        return FacadeSupport.execute(() -> billService.download(channelCode, bizDate));
    }

    @Override
    public Result<List<ChannelBillDTO>> listBills(String channelCode, String bizDate) {
        return FacadeSupport.execute(() -> billService.list(channelCode, bizDate));
    }

    @Override
    public Result<ChannelBillDTO> getBillDetail(long billId) {
        return FacadeSupport.execute(() -> billService.detail(billId));
    }

    @Override
    public Result<ChannelBillDTO> reconcileBill(long billId) {
        return FacadeSupport.execute(() -> billService.reconcile(billId));
    }
}