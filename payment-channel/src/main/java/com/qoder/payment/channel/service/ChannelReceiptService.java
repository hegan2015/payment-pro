package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelReceiptLogDO;
import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelReceiptLogMapper;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelReceiptRequest;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 渠道终态回执接收（CH-1）：幂等 + 乱序安全。
 *
 * <p>三重护栏：① 回执日志 UNIQUE(biz_no,status) 去重；② channel_trade 状态 CAS
 * （SUBMITTED→终态只翻一次，终态先到、中间态/重复后到被吸收）；③ 金额一致性校验。
 * 同步渠道（PREAUTH）终态由 TCC Confirm 驱动，回执一律忽略，保证同步路径行为不变。
 *
 * <p>本服务不反向调用支付域：支付单由 PaymentReconcileJob 查询收敛，
 * 或经支付侧 reconverge 入口立即收敛（避免 channel↔payment 环依赖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelReceiptService {

    private static final Set<String> TERMINAL = Set.of("CAPTURED", "REFUNDED", "FAILED");

    private final ChannelTradeMapper channelTradeMapper;
    private final ChannelReceiptLogMapper receiptLogMapper;

    public ChannelTradeDTO receiveReceipt(ChannelReceiptRequest request) {
        if (request == null || request.getBizNo() == null || request.getBizNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "bizNo 必填");
        }
        if (request.getStatus() == null || request.getStatus().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 必填");
        }
        String bizNo = request.getBizNo().trim();
        String status = request.getStatus().trim().toUpperCase();
        ChannelTradeDO trade = channelTradeMapper.selectByBizNo(bizNo);
        if (trade == null) {
            throw new BizException(ResultCode.NOT_FOUND, "渠道交易不存在: " + bizNo);
        }
        if (!TERMINAL.contains(status)) {
            log.info("忽略中间态回执 bizNo={} status={} current={}", bizNo, status, trade.getStatus());
            return ChannelService.toDTO(trade);
        }
        String success = "REFUND".equals(trade.getType()) ? "REFUNDED" : "CAPTURED";
        if (!status.equals(success) && !"FAILED".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "回执终态与渠道交易类型不匹配: type=" + trade.getType() + " status=" + status);
        }
        if (request.getAmount() != null && !request.getAmount().equals(trade.getAmount())) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "回执金额与渠道交易不一致: " + request.getAmount() + " != " + trade.getAmount());
        }
        if (!"SUBMITTED".equals(trade.getStatus())) {
            // 已终态（重复回执）/ 已撤销 / 同步预授权（终态由 TCC Confirm 驱动）：幂等吸收，不改写
            log.info("回执幂等吸收 bizNo={} receiptStatus={} current={}", bizNo, status, trade.getStatus());
            return ChannelService.toDTO(trade);
        }

        long now = System.currentTimeMillis();
        ChannelReceiptLogDO receipt = new ChannelReceiptLogDO();
        receipt.setBizNo(bizNo);
        receipt.setChannelOrderNo(request.getChannelOrderNo());
        receipt.setStatus(status);
        receipt.setRawPayload(request.getRaw());
        receipt.setCreatedAt(now);
        try {
            receiptLogMapper.insert(receipt);
        } catch (DuplicateKeyException e) {
            log.info("重复回执去重命中 bizNo={} status={}", bizNo, status);
        }

        int updated = channelTradeMapper.casTerminal(bizNo, "SUBMITTED", status, request.getReason(), now);
        if (updated == 1) {
            if (request.getChannelOrderNo() != null && !request.getChannelOrderNo().isBlank()) {
                channelTradeMapper.updateChannelOrderNo(bizNo, request.getChannelOrderNo());
            }
            log.info("渠道终态回执落地 bizNo={} status={} channelOrderNo={}", bizNo, status, request.getChannelOrderNo());
        } else {
            log.info("渠道终态 CAS 未命中（并发回执/撤销先到）bizNo={} status={}", bizNo, status);
        }
        return ChannelService.toDTO(channelTradeMapper.selectByBizNo(bizNo));
    }
}