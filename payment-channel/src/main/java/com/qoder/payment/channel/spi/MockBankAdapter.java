package com.qoder.payment.channel.spi;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CH-2a 模拟银行适配器（重构自原 ChannelService 的渠道侧逻辑，<b>同步路径行为逐字节不变</b>）：
 * 卡号尾号 0000 → 银行拒绝；PAY 预授权 → CAPTURED/VOIDED；REFUND 原单必须 CAPTURED。
 *
 * <p>永久保留为 local 降级与混沌靶（§5.3 红线）。异步开关 {@code payment.channel.async=true} 时
 * PAY 返回 PROCESSING（受理未扣款），终态由 {@code MockBankAsyncJob} 落地（不推回执 = 回执丢失靶）。
 *
 * <p>本适配器<b>不写 channel_trade</b>（本地流水由 ChannelService 单点落库）；
 * 账单由本地终态流水生成（mock 账单，local 零依赖降级）。
 */
@Slf4j
public class MockBankAdapter implements ChannelAdapter {

    public static final String TYPE = "MOCK";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final String channelCode;
    private final ChannelTradeMapper tradeMapper;
    private final ChannelFaultInjector faults;
    private final boolean async;

    public MockBankAdapter(String channelCode, ChannelTradeMapper tradeMapper,
                           ChannelFaultInjector faults, boolean async) {
        this.channelCode = channelCode;
        this.tradeMapper = tradeMapper;
        this.faults = faults;
        this.async = async;
    }

    @Override
    public String channelCode() {
        return channelCode;
    }

    @Override
    public String adapterType() {
        return TYPE;
    }

    @Override
    public String env() {
        return "PROD";
    }

    @Override
    public boolean asyncCapable() {
        // false = 同步可用（默认路径），无需异步 opt-in 即可被路由选中；
        // payment.channel.async=true 时本适配器按 CH-1 既有语义返回 PROCESSING（回执丢失混沌靶）。
        return false;
    }

    @Override
    public ChannelResult pay(ChannelPayRequest request) {
        ChannelResult declined = validateCard(request.getCardNo());
        if (declined != null) {
            return declined;
        }
        if (async) {
            return ChannelResult.processing(null, "模拟银行异步受理（未扣款，等终态）");
        }
        return ChannelResult.success(null);
    }

    @Override
    public ChannelResult refund(ChannelRefundRequest request) {
        ChannelResult declined = validateCard(request.getCardNo());
        if (declined != null) {
            return declined;
        }
        ChannelTradeDO orig = tradeMapper.selectByBizNo(request.getOrigBizNo());
        if (orig == null || !"CAPTURED".equals(orig.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "原渠道交易未扣款，不可退款: " + request.getOrigBizNo());
        }
        return ChannelResult.success(null);
    }

    /** 卡号校验：文案与重构前逐字一致（PARAM_ERROR 卡号必填 / BIZ_ERROR 渠道拒绝）。 */
    private ChannelResult validateCard(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡号必填");
        }
        if (cardNo.endsWith("0000")) {
            return ChannelResult.declined("渠道拒绝: 银行风控拦截 (卡号尾号 0000)");
        }
        return null;
    }

    @Override
    public ChannelResult query(String bizNo) {
        ChannelTradeDO trade = tradeMapper.selectByBizNo(bizNo);
        ChannelResult r = new ChannelResult();
        if (trade == null) {
            r.setStatus(ChannelResult.FAILED);
            r.setChannelStatus("NOT_FOUND");
            r.setReason("渠道无此交易: " + bizNo);
            return r;
        }
        r.setChannelOrderNo(trade.getChannelOrderNo());
        r.setChannelStatus(trade.getStatus());
        r.setAmount(trade.getAmount());
        r.setReason(trade.getReason());
        switch (trade.getStatus()) {
            case "CAPTURED", "REFUNDED", "PREAUTH" -> r.setStatus(ChannelResult.SUCCESS);
            case "SUBMITTED" -> r.setStatus(ChannelResult.PROCESSING);
            default -> r.setStatus(ChannelResult.FAILED);
        }
        return r;
    }

    @Override
    public ChannelResult close(String bizNo) {
        // 模拟银行无外部单可关；本地撤销由 TCC Cancel（ChannelService.voidAuth）驱动
        return ChannelResult.success(null);
    }

    @Override
    public boolean billSupported() {
        return true;
    }

    /** mock 账单：由本渠道当日终态流水生成 CSV（含历史无渠道码行，它们属于模拟银行）。 */
    @Override
    public ChannelBillFile downloadBill(String bizDate) {
        LocalDate day = LocalDate.parse(bizDate, DAY);
        long from = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long to = from + 86_400_000L - 1;
        List<ChannelTradeDO> trades = tradeMapper.selectTerminalInRange(from, to);
        ChannelFaultInjector.BillFault fault = faults.billFault(channelCode);
        StringBuilder sb = new StringBuilder("channel_order_no,biz_no,type,amount,status,trade_time\n");
        long count = 0;
        long sum = 0;
        for (ChannelTradeDO t : trades) {
            if (!belongsHere(t)) {
                continue;
            }
            if (fault != null && t.getBizNo().equals(fault.getDropBizNo())) {
                continue;
            }
            long amount = t.getAmount() == null ? 0L : t.getAmount();
            if (fault != null && t.getBizNo().equals(fault.getAmountBizNo())) {
                amount += fault.getAmountDelta();
            }
            String orderNo = t.getChannelOrderNo() == null || t.getChannelOrderNo().isBlank()
                    ? t.getBizNo() : t.getChannelOrderNo();
            sb.append(orderNo).append(',').append(t.getBizNo()).append(',').append(t.getType()).append(',')
                    .append(amount).append(',').append(t.getStatus()).append(',')
                    .append(t.getCreatedAt() == null ? from : t.getCreatedAt()).append('\n');
            count++;
            sum += Math.abs(amount);
        }
        if (fault != null) {
            for (String extra : fault.getExtraRows()) {
                sb.append(extra).append('\n');
                String[] cols = extra.split(",");
                count++;
                if (cols.length >= 4) {
                    try {
                        sum += Math.abs(Long.parseLong(cols[3].trim()));
                    } catch (NumberFormatException ignored) {
                        // 非法金额行照样写入文件：由解析器判定失败（不静默丢行、不掩盖脏数据）
                        sum += 0L;
                    }
                }
            }
        }
        sb.append("#TOTAL,").append(count).append(',').append(sum + (fault == null ? 0L : fault.getTotalDelta())).append('\n');
        return ChannelBillFile.csv(channelCode, bizDate, sb.toString(), count,
                sum + (fault == null ? 0L : fault.getTotalDelta()));
    }

    // ---------- CH-6 签约/解约 SPI（沙箱模拟银行，不接真实银行委托扣款） ----------

    @Override
    public boolean signCapable() {
        return true;
    }

    /**
     * 渠道侧签约：卡号规则与 pay 同一口径（尾号 0000 银行拒绝，受理前拒绝不落协议行）；
     * 渠道协议号确定性派生 {@code MOCKSIGN-<signNo>} ⇒ 同平台协议号重复签约幂等。
     * 签约不是资金动作，同步返回终态（与 async 开关无关，不影响既有 PAY 异步语义）。
     */
    @Override
    public ChannelResult sign(String signNo, String cardNo, Long singleLimit) {
        ChannelResult declined = validateCard(cardNo);
        if (declined != null) {
            return declined;
        }
        return ChannelResult.success("MOCKSIGN-" + signNo);
    }

    /** 渠道侧解约：幂等（已解约重复调用同样 SUCCESS，不产生副作用）。 */
    @Override
    public ChannelResult unsign(String channelSignNo) {
        return ChannelResult.success(channelSignNo);
    }

    private boolean belongsHere(ChannelTradeDO t) {
        String code = t.getChannelCode();
        // 历史行（CH-2a 之前）无渠道码，归属模拟银行主渠道
        return channelCode.equals(code) || (code == null && "MOCK_BANK".equals(channelCode));
    }
}