package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.channel.route.RouteRuleConfig;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelResult;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 渠道查询 SPI（CH-1，CH-2a 委托适配器）。
 *
 * <ul>
 *   <li>{@link #queryChannelTrade}：本地 channel_trade 的 CAS 快照（支付补单决策依据），
 *       <b>语义与 CH-1 完全一致</b>，绝不改写状态；</li>
 *   <li>{@link #queryChannelSide}：委托 {@code ChannelAdapter#query} 取渠道方视角状态（只读），
 *       供运维核对与补单 job 判定渠道终态；渠道不可达时返回 UNKNOWN，不臆断失败。</li>
 * </ul>
 *
 * <p>模拟银行异步受理的终态落地由 {@link MockBankAsyncJob} 完成，沙箱渠道由
 * {@link SandboxReceiptJob}（渠道侧推送）与 {@link SandboxQueryJob}（平台侧补单）收敛，
 * 回执统一经 {@link ChannelReceiptService} 的去重 + CAS + 金额护栏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelQueryService {

    private final ChannelTradeMapper channelTradeMapper;
    private final ChannelAdapterRegistry registry;
    private final RouteRuleConfig ruleConfig;

    public ChannelTradeDTO queryChannelTrade(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        ChannelTradeDO trade = channelTradeMapper.selectByBizNo(bizNo);
        if (trade == null) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道交易不存在: " + bizNo);
        }
        return ChannelService.toDTO(trade);
    }

    /** 渠道侧查询（SPI 委托，只读）：返回渠道方状态视图，不改写本地任何状态。 */
    public ChannelTradeDTO queryChannelSide(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        ChannelTradeDO local = channelTradeMapper.selectByBizNo(bizNo);
        String code = local != null && local.getChannelCode() != null && !local.getChannelCode().isBlank()
                ? local.getChannelCode() : ruleConfig.defaultChannel();
        ChannelAdapter adapter = registry.find(code);
        if (adapter == null) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道适配器未注册: " + code);
        }
        ChannelResult result = adapter.query(bizNo);
        if (local == null && "NOT_FOUND".equals(result.getChannelStatus())) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道交易不存在: " + bizNo);
        }
        ChannelTradeDTO dto = local != null ? ChannelService.toDTO(local) : new ChannelTradeDTO();
        dto.setBizNo(bizNo);
        dto.setChannelCode(code);
        dto.setStatus(result.getChannelStatus());
        dto.setReason(result.getReason());
        if (result.getChannelOrderNo() != null && !result.getChannelOrderNo().isBlank()) {
            dto.setChannelOrderNo(result.getChannelOrderNo());
        }
        if (result.getAmount() != null) {
            dto.setAmount(result.getAmount());
        }
        return dto;
    }
}