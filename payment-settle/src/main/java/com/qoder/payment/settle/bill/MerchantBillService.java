package com.qoder.payment.settle.bill;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.settle.MerchantBillDTO;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.ShareSettleDTO;
import com.qoder.payment.settle.mapper.MerchantBillMapper;
import com.qoder.payment.settle.mapper.SettleAggregateMapper;
import com.qoder.payment.settle.service.SettleShareService;
import com.qoder.payment.settle.service.SettleSupport;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ST-5 商户账单/对账单（日/月）：金额口径 = Σ结算单（<b>跨片顶层 SUM 归并</b>）+ Σ分账明细，
 * 与结算批次、账务恒等式同源；CSV 明细 + 汇总可下载。
 *
 * <p><b>幂等</b>：{@code (MERCHANT_BILL, merchantId:bizDate:billType)} + UNIQUE 三列键，
 * 重复生成不重不改金额（已存在直接返回，文件缺失时按同一口径补齐）。
 *
 * <p><b>跨片正确性</b>：gross/fee/net/refund 全部来自 {@code SettleAggregateMapper} 的顶层 SUM
 * （分片态各片 SUM 再相加，单库态同一 SQL），并与逐笔明细求和交叉校验，误差必须为 0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantBillService {

    public static final String KEY_BILL_DIR = "settle.bill.dir";
    public static final String DEFAULT_BILL_DIR = "data/bills";
    public static final String DAILY = "DAILY";
    public static final String MONTHLY = "MONTHLY";

    private final MerchantBillMapper billMapper;
    private final SettleAggregateMapper aggregateMapper;
    private final SettleShareService shareService;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;
    private final DynamicConfig dynamicConfig;

    public MerchantBillDTO generate(String merchantId, Long bizDate, String billType) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "merchantId 必填");
        }
        String type = billType == null || billType.isBlank() ? DAILY : billType.toUpperCase();
        if (!DAILY.equals(type) && !MONTHLY.equals(type)) {
            throw new BizException(ResultCode.PARAM_ERROR, "billType 仅支持 DAILY/MONTHLY");
        }
        long anchor = bizDate == null ? System.currentTimeMillis() : bizDate;
        long from = DAILY.equals(type) ? SettleSupport.dayStart(anchor) : SettleSupport.monthStart(anchor);
        long to = DAILY.equals(type) ? from + SettleSupport.DAY_MS : SettleSupport.nextMonthStart(from);
        String idemKey = merchantId + ":" + from + ":" + type;

        return idempotentTemplate.execute("MERCHANT_BILL", idemKey, MerchantBillDTO.class, () -> {
            MerchantBillDTO existing = billMapper.selectByKey(merchantId, from, type);
            if (existing != null) {
                ensureFile(existing, from, to);
                return decorate(existing);
            }
            long gross = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumPayGross(merchantId, from, to)));
            long refund = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumRefundGross(merchantId, from, to)));
            long fee = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumFee(merchantId, from, to)));
            long net = support.readYourWrite(() -> SettleSupport.nz(aggregateMapper.sumNet(merchantId, from, to)));
            int count = support.readYourWrite(() -> aggregateMapper.countRange(merchantId, from, to));
            List<SettleRecordDTO> records = support.readYourWrite(() -> aggregateMapper.selectRange(merchantId, from, to));
            long detailNet = 0;
            for (SettleRecordDTO record : records) {
                detailNet += "PAY".equals(record.getType())
                        ? SettleSupport.nz(record.getNet()) : -SettleSupport.nz(record.getNet());
            }
            if (net != gross - refund - fee || detailNet != net || records.size() != count) {
                throw new BizException(ResultCode.BIZ_ERROR, "账单口径校验失败 net=" + net + " gross=" + gross
                        + " refund=" + refund + " fee=" + fee + " detailNet=" + detailNet + " count=" + count);
            }
            long share = shareService.shareNetOf(merchantId, from, to);

            MerchantBillDTO bill = new MerchantBillDTO();
            bill.setId(support.newId("M"));
            bill.setMerchantId(merchantId);
            bill.setBizDate(from);
            bill.setBillType(type);
            bill.setGross(gross);
            bill.setFee(fee);
            bill.setNet(net);
            bill.setRefund(refund);
            bill.setShare(share);
            bill.setRecordCount(count);
            bill.setStatus("GENERATED");
            bill.setCreatedAt(System.currentTimeMillis());
            bill.setFilePath(filePath(bill));
            billMapper.insert(bill);
            ensureFile(bill, from, to);
            log.info("ST-5 账单生成 billId={} merchant={} bizDate={} type={} gross={} fee={} net={} refund={} share={} count={}",
                    bill.getId(), merchantId, SettleSupport.dayText(from), type, gross, fee, net, refund, share, count);
            return decorate(bill);
        });
    }

    public List<MerchantBillDTO> list(String merchantId) {
        List<MerchantBillDTO> bills = support.readYourWrite(() -> merchantId == null || merchantId.isBlank()
                ? billMapper.selectAll() : billMapper.selectByMerchant(merchantId));
        bills.forEach(MerchantBillService::decorate);
        return bills;
    }

    public MerchantBillDTO get(String billId) {
        MerchantBillDTO bill = support.readYourWrite(() -> billMapper.selectById(billId));
        if (bill == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账单不存在: " + billId);
        }
        return decorate(bill);
    }

    /** CSV 全文（文件优先，缺失按同一口径重生成，保证下载永不 404）。 */
    public String csv(String billId) {
        MerchantBillDTO bill = get(billId);
        long from = bill.getBizDate();
        long to = DAILY.equals(bill.getBillType()) ? from + SettleSupport.DAY_MS : SettleSupport.nextMonthStart(from);
        Path path = Path.of(bill.getFilePath() == null ? filePath(bill) : bill.getFilePath());
        if (Files.exists(path)) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("ST-5 账单文件读取失败，按口径重生成 billId={}: {}", billId, e.getMessage());
            }
        }
        return render(bill, from, to);
    }

    private void ensureFile(MerchantBillDTO bill, long from, long to) {
        String dir = dynamicConfig.get(KEY_BILL_DIR, DEFAULT_BILL_DIR);
        Path path = Path.of(dir, fileName(bill));
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, render(bill, from, to), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // 文件落盘失败不影响账单权威数据（DB），下载走重生成兜底
            log.error("ST-5 账单文件写入失败 billId={} path={}: {}", bill.getId(), path, e.getMessage());
        }
    }

    private String render(MerchantBillDTO bill, long from, long to) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SUMMARY\n");
        sb.append("merchantId,bizDate,billType,gross,fee,net,refund,share,recordCount\n");
        sb.append(bill.getMerchantId()).append(',')
                .append(DAILY.equals(bill.getBillType()) ? SettleSupport.dayText(from) : SettleSupport.monthText(from))
                .append(',').append(bill.getBillType()).append(',')
                .append(bill.getGross()).append(',').append(bill.getFee()).append(',').append(bill.getNet())
                .append(',').append(bill.getRefund()).append(',').append(bill.getShare()).append(',')
                .append(bill.getRecordCount()).append('\n');
        sb.append("# RECORDS\n");
        sb.append("recordId,type,paymentId,orderId,gross,fee,net,method,status,createdAt\n");
        List<SettleRecordDTO> records = support.readYourWrite(
                () -> aggregateMapper.selectRange(bill.getMerchantId(), from, to));
        for (SettleRecordDTO record : records) {
            sb.append(record.getId()).append(',').append(record.getType()).append(',')
                    .append(nz(record.getPaymentId())).append(',').append(nz(record.getOrderId())).append(',')
                    .append(SettleSupport.nz(record.getGross())).append(',').append(SettleSupport.nz(record.getFee()))
                    .append(',').append(SettleSupport.nz(record.getNet())).append(',').append(nz(record.getMethod()))
                    .append(',').append(nz(record.getStatus())).append(',').append(record.getCreatedAt()).append('\n');
        }
        sb.append("# SHARES\n");
        sb.append("shareId,receiverAccountId,type,amount,fee,net,createdAt\n");
        for (ShareSettleDTO detail : shareService.range(bill.getMerchantId(), from, to)) {
            sb.append(detail.getShareId()).append(',').append(detail.getReceiverAccountId()).append(',')
                    .append(detail.getType()).append(',').append(SettleSupport.nz(detail.getAmount())).append(',')
                    .append(SettleSupport.nz(detail.getFee())).append(',').append(SettleSupport.nz(detail.getNet()))
                    .append(',').append(detail.getCreatedAt()).append('\n');
        }
        return sb.toString();
    }

    private String filePath(MerchantBillDTO bill) {
        return Path.of(dynamicConfig.get(KEY_BILL_DIR, DEFAULT_BILL_DIR), fileName(bill)).toString();
    }

    private static String fileName(MerchantBillDTO bill) {
        String period = DAILY.equals(bill.getBillType())
                ? SettleSupport.dayText(bill.getBizDate()) : SettleSupport.monthText(bill.getBizDate());
        return bill.getMerchantId() + "-" + period + "-" + bill.getBillType() + ".csv";
    }

    private static MerchantBillDTO decorate(MerchantBillDTO bill) {
        if (bill != null && bill.getBizDate() != null) {
            bill.setBizDateText(DAILY.equals(bill.getBillType())
                    ? SettleSupport.dayText(bill.getBizDate()) : SettleSupport.monthText(bill.getBizDate()));
        }
        return bill;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}