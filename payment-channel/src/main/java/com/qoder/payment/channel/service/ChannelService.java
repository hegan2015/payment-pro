package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模拟银行渠道：卡号以 0000 结尾触发银行拒绝。
 * PAY：PREAUTH → CAPTURED / VOIDED；REFUND：PREAUTH → REFUNDED / VOIDED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelTradeMapper channelTradeMapper;

    public List<ChannelTradeDTO> listTrades() {
        return channelTradeMapper.selectAll().stream().map(t -> {
            ChannelTradeDTO dto = new ChannelTradeDTO();
            BeanUtils.copyProperties(t, dto);
            return dto;
        }).toList();
    }

    /** TCC Try：银行预授权（拒绝规则在此生效）。 */
    public void preauth(ChannelTccContext ctx, String type) {
        if (ctx.getCardNo() == null || ctx.getCardNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡号必填");
        }
        if (ctx.getCardNo().endsWith("0000")) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道拒绝: 银行风控拦截 (卡号尾号 0000)");
        }
        if ("REFUND".equals(type)) {
            ChannelTradeDO orig = channelTradeMapper.selectByBizNo(ctx.getOrigBizNo());
            if (orig == null || !"CAPTURED".equals(orig.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "原渠道交易未扣款，不可退款: " + ctx.getOrigBizNo());
            }
        }
        ChannelTradeDO trade = new ChannelTradeDO();
        trade.setBizNo(ctx.getBizNo());
        trade.setType(type);
        trade.setOrigBizNo(ctx.getOrigBizNo());
        trade.setCardNo(ctx.getCardNo());
        trade.setAmount(ctx.getAmount());
        trade.setStatus("PREAUTH");
        trade.setCreatedAt(System.currentTimeMillis());
        channelTradeMapper.insert(trade);
        log.info("渠道预授权(TCC Try) bizNo={} type={} amount={}", ctx.getBizNo(), type, ctx.getAmount());
    }

    /** TCC Confirm：预授权转扣款/退款成功。 */
    public void capture(ChannelTccContext ctx, String type) {
        String to = "REFUND".equals(type) ? "REFUNDED" : "CAPTURED";
        channelTradeMapper.casStatus(ctx.getBizNo(), "PREAUTH", to);
        log.info("渠道{}(TCC Confirm) bizNo={}", "REFUND".equals(type) ? "退款" : "扣款", ctx.getBizNo());
    }

    /** TCC Cancel：撤销预授权。 */
    public void voidAuth(ChannelTccContext ctx) {
        channelTradeMapper.casStatus(ctx.getBizNo(), "PREAUTH", "VOIDED");
        log.info("渠道撤销预授权(TCC Cancel) bizNo={}", ctx.getBizNo());
    }
}