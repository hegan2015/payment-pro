package com.qoder.payment.settle.service;

import com.qoder.payment.common.db.IdGenerator;
import com.qoder.payment.common.db.ShardMirror;
import com.qoder.payment.common.db.ShardRuntime;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.payment.facade.settle.SettlePayRequest;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.SettleRefundRequest;
import com.qoder.payment.facade.settle.SettleSummaryDTO;
import com.qoder.payment.settle.dataobject.SettleRecordDO;
import com.qoder.payment.settle.mapper.SettleRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettleService {

    private final SettleRecordMapper settleRecordMapper;
    private final IdempotentTemplate idempotentTemplate;

    // I-5a：仅在 payment.sharding.enabled=true 时存在（local/未开启域 getIfAvailable()==null，零影响）
    private final ObjectProvider<ShardRuntime> shardRuntime;
    private final ObjectProvider<IdGenerator> idGenerator;
    private final ObjectProvider<ShardMirror> shardMirror;

    public SettleRecordDTO recordPay(SettlePayRequest request) {
        return idempotentTemplate.execute("SETTLE_PAY", request.getPaymentId(), SettleRecordDTO.class, () -> {
            SettleRecordDO record = new SettleRecordDO();
            record.setId(newRecordId());
            record.setType("PAY");
            record.setBizId(request.getPaymentId());
            record.setPaymentId(request.getPaymentId());
            record.setOrderId(request.getOrderId());
            record.setMerchantId(request.getMerchantId());
            record.setGross(request.getAmount());
            record.setFee(request.getFee());
            record.setNet(request.getAmount() - request.getFee());
            record.setMethod(request.getMethod());
            record.setStatus("SETTLED");
            record.setCreatedAt(System.currentTimeMillis());
            settleRecordMapper.insert(record);
            mirrorToSingleTable(record);
            log.info("结算登记 paymentId={} gross={} fee={} net={}", record.getBizId(), record.getGross(), record.getFee(), record.getNet());
            return toDTO(record);
        });
    }

    public SettleRecordDTO recordRefund(SettleRefundRequest request) {
        return idempotentTemplate.execute("SETTLE_REFUND", request.getBizId(), SettleRecordDTO.class, () -> {
            // 读己之写：原支付结算单可能刚由 outbox 投递写入，强制走主库
            SettleRecordDO pay = readYourWrite(() -> settleRecordMapper.selectByBiz(request.getPaymentId(), "PAY"));
            if (pay == null) {
                throw new BizException(ResultCode.BIZ_ERROR, "原支付结算单缺失: " + request.getPaymentId());
            }
            SettleRecordDO record = new SettleRecordDO();
            record.setId(newRecordId());
            record.setType("REFUND");
            record.setBizId(request.getBizId());
            record.setPaymentId(request.getPaymentId());
            record.setOrderId(pay.getOrderId());
            record.setMerchantId(pay.getMerchantId());
            record.setGross(request.getAmount());
            record.setFee(request.getFeeRefund());
            record.setNet(request.getAmount() - request.getFeeRefund());
            record.setMethod(pay.getMethod());
            record.setStatus("SETTLED");
            record.setCreatedAt(System.currentTimeMillis());
            settleRecordMapper.insert(record);
            mirrorToSingleTable(record);
            log.info("退款结算登记 bizId={} paymentId={} amount={} feeRefund={}", record.getBizId(), request.getPaymentId(), request.getAmount(), request.getFeeRefund());
            return toDTO(record);
        });
    }

    public List<SettleRecordDTO> records(String merchantId) {
        // 结算读己之写走主；merchantId 非空时 ShardingSphere 精确路由到单片
        List<SettleRecordDO> records = readYourWrite(() -> merchantId == null || merchantId.isBlank()
                ? settleRecordMapper.selectAll()
                : settleRecordMapper.selectByMerchant(merchantId));
        return records.stream().map(SettleService::toDTO).toList();
    }

    public SettleSummaryDTO summary() {
        return readYourWrite(() -> {
            SettleSummaryDTO dto = new SettleSummaryDTO();
            dto.setPayCount(settleRecordMapper.payCount());
            dto.setTotalGross(nullToZero(settleRecordMapper.totalGross()));
            dto.setTotalFee(nullToZero(settleRecordMapper.totalFee()));
            dto.setTotalNet(nullToZero(settleRecordMapper.totalNet()));
            dto.setTotalRefund(nullToZero(settleRecordMapper.totalRefund()));
            return dto;
        });
    }

    /**
     * I-5a 取号：分片开启走全局发号（雪花，避免各片 sequences 重号 E-7）；
     * 未开启（local 默认）仍走 {@code next_value_for('seq_settle')}，ID 形态与改造前逐字节一致。
     */
    private String newRecordId() {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        if (runtime != null && runtime.isActive()) {
            return idGenerator.getObject().next("S");
        }
        return "S" + settleRecordMapper.nextId();
    }

    /** I-5a 影子写：分片期 additive 镜像回原单表，保障 enabled=false 一键回滚后结果一致。 */
    private void mirrorToSingleTable(SettleRecordDO record) {
        ShardMirror mirror = shardMirror.getIfAvailable();
        if (mirror == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("type", record.getType());
        row.put("biz_id", record.getBizId());
        row.put("payment_id", record.getPaymentId());
        row.put("order_id", record.getOrderId());
        row.put("merchant_id", record.getMerchantId());
        row.put("gross", record.getGross());
        row.put("fee", record.getFee());
        row.put("net", record.getNet());
        row.put("method", record.getMethod());
        row.put("status", record.getStatus());
        row.put("created_at", record.getCreatedAt());
        mirror.insert("settle_record", row);
    }

    private <T> T readYourWrite(Supplier<T> action) {
        ShardRuntime runtime = shardRuntime.getIfAvailable();
        return runtime == null ? action.get() : runtime.readYourWrite(action);
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static SettleRecordDTO toDTO(SettleRecordDO source) {
        SettleRecordDTO dto = new SettleRecordDTO();
        BeanUtils.copyProperties(source, dto);
        return dto;
    }
}