package com.qoder.payment.channel.service;

import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.channel.route.ChannelMetricsCollector;
import com.qoder.payment.channel.route.ChannelRouter;
import com.qoder.payment.channel.route.RouteRuleConfig;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import com.qoder.payment.facade.channel.ChannelTccContext;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.payment.facade.channel.RouteResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 渠道受理编排（CH-2a 重构：渠道侧逻辑下沉 SPI 适配器，本类只做路由 + 本地流水单点落库）。
 *
 * <p><b>同步路径行为逐字节不变</b>（默认 {@code payment.channel.async=false}）：
 * 模拟银行 MOCK_BANK 经 SPI 受理，卡号尾号 0000 拒绝文案、PARAM_ERROR/BIZ_ERROR 错误码、
 * PREAUTH → CAPTURED/VOIDED（PAY）与 PREAUTH → REFUNDED/VOIDED（REFUND）状态机、
 * 日志文案与落库字段全部与重构前一致；BALANCE/无渠道分支不经本类，语义不受影响（§0.7 红线）。
 *
 * <p>CH-3 路由择优 + 故障切换：受理前经 {@link ChannelRouter} 硬过滤（限额/维护/熔断/异步 opt-in）
 * 并加权评分择优；仅当适配器明确报告「确未受理」才沿候选序列切换，
 * 已受理后状态未知一律 PROCESSING（交查询补单），<b>一个 bizNo 只落一行、只归属一个渠道</b>
 * （channel_trade.biz_no PK 结构性保证），切换不产生悬挂单；候选耗尽 → 抛错 → TCC Cancel 空回滚。
 *
 * <p>CH-5 限额：受理前 {@link ChannelLimitService#checkBeforeAccept} 二次校验（指定渠道亦校验），
 * 超限直接拒绝、不落行、不累计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelTradeMapper channelTradeMapper;
    private final ChannelAdapterRegistry registry;
    private final ChannelRouter router;
    private final RouteRuleConfig ruleConfig;
    private final ChannelLimitService limitService;
    private final ChannelMetricsCollector metrics;

    /** 异步渠道开关：默认 false（同步模拟银行），双 profile 由同一环境变量 PAYMENT_CHANNEL_ASYNC 控制。 */
    @Value("${payment.channel.async:false}")
    private boolean async;

    public List<ChannelTradeDTO> listTrades() {
        return channelTradeMapper.selectAll().stream().map(ChannelService::toDTO).toList();
    }

    /** TCC Try：路由择优 → 渠道受理（拒绝规则在适配器内生效）；异步模式下 PAY 为受理 SUBMITTED。 */
    public void preauth(ChannelTccContext ctx, String type) {
        boolean refund = "REFUND".equals(type);
        long amount = ctx.getAmount() == null ? 0L : ctx.getAmount();

        ChannelRouteRequest routeRequest = new ChannelRouteRequest();
        routeRequest.setAmount(amount);
        routeRequest.setCardNo(ctx.getCardNo());
        routeRequest.setType(type);
        String specified = refund ? resolveOrigChannel(ctx.getOrigBizNo()) : ctx.getChannelCode();
        routeRequest.setChannelCode(specified);
        // 指定渠道：先做限额校验（明确「渠道限额拒绝」文案），再路由；二者都在受理前，超限不留行
        if (!refund && specified != null && !specified.isBlank()) {
            limitService.checkBeforeAccept(specified, amount);
        }
        RouteResultDTO route = router.routeForAccept(routeRequest);
        List<String> candidates = router.eligibleOrder(route);
        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道路由拒绝: 无可用渠道 " + route.getReason());
        }
        int maxAttempts = ruleConfig.failoverEnabled()
                ? Math.min(ruleConfig.maxAttempts(), candidates.size()) : 1;

        ChannelResult result = null;
        String selected = null;
        String lastReason = null;
        for (int i = 0; i < maxAttempts; i++) {
            String code = candidates.get(i);
            ChannelAdapter adapter = registry.resolve(code);
            long start = System.currentTimeMillis();
            ChannelResult attempt;
            try {
                // CH-5：受理前限额校验（超限拒绝发生在落行之前，不留悬挂单）；
                // 退款不占渠道限额（资金必须可退，与路由退款分支口径一致）
                if (!refund) {
                    limitService.checkBeforeAccept(code, amount);
                }
                attempt = refund ? adapter.refund(refundRequest(ctx, code)) : adapter.pay(payRequest(ctx, code));
            } catch (BizException e) {
                // 业务拒绝（卡号规则/原单未扣款/限额）：原样抛出，文案与错误码不变，不做故障切换
                metrics.record(code, false, System.currentTimeMillis() - start);
                throw e;
            } catch (RuntimeException e) {
                metrics.record(code, false, System.currentTimeMillis() - start);
                attempt = ChannelResult.notAccepted("渠道适配器异常（未受理）: " + e.getMessage());
            }
            long cost = System.currentTimeMillis() - start;
            if (attempt == null) {
                attempt = ChannelResult.notAccepted("渠道适配器返回空结果（未受理）");
            }
            if (attempt.isFailed()) {
                metrics.record(code, false, cost);
                lastReason = attempt.getReason();
                if (!attempt.isFailoverAllowed()) {
                    throw new BizException(ResultCode.BIZ_ERROR, attempt.getReason());
                }
                if (i + 1 < maxAttempts) {
                    log.warn("CH-3 故障切换 from={} to={} bizNo={} reason={}", code, candidates.get(i + 1),
                            ctx.getBizNo(), lastReason);
                    MetricsSupport.count("channel_failover", "from", code, "to", candidates.get(i + 1));
                    continue;
                }
                throw new BizException(ResultCode.BIZ_ERROR, "渠道受理失败（候选耗尽）: " + lastReason);
            }
            metrics.record(code, true, cost);
            result = attempt;
            selected = code;
            break;
        }
        if (result == null || selected == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道受理失败（候选耗尽）: " + lastReason);
        }
        if (result.isProcessing() && !async) {
            // 兜底护栏：同步 stack 绝不留 SUBMITTED（支付侧会直接 commit → 悬挂），改为明确拒绝
            throw new BizException(ResultCode.DEGRADED, "异步渠道需开启 PAYMENT_CHANNEL_ASYNC=true，同步模式拒绝受理: "
                    + selected);
        }

        boolean asyncSubmit = async && "PAY".equals(type) && result.isProcessing();
        long now = System.currentTimeMillis();
        ChannelTradeDO trade = new ChannelTradeDO();
        trade.setBizNo(ctx.getBizNo());
        trade.setType(type);
        trade.setOrigBizNo(ctx.getOrigBizNo());
        trade.setCardNo(ctx.getCardNo());
        trade.setAmount(ctx.getAmount());
        trade.setStatus(asyncSubmit ? "SUBMITTED" : "PREAUTH");
        trade.setCreatedAt(now);
        trade.setSubmittedAt(asyncSubmit ? now : null);
        trade.setChannelCode(selected);
        trade.setChannelOrderNo(result.getChannelOrderNo());
        channelTradeMapper.insert(trade);
        if (!refund) {
            limitService.accumulate(selected, amount);
        }
        log.info("渠道{}(TCC Try) bizNo={} type={} amount={}", asyncSubmit ? "异步受理" : "预授权",
                ctx.getBizNo(), type, ctx.getAmount());
        log.info("CH-2a/CH-3 渠道受理落痕 bizNo={} channelCode={} channelOrderNo={} routeReason={}",
                ctx.getBizNo(), selected, result.getChannelOrderNo(), route.getReason());
    }

    /**
     * TCC Confirm：预授权转扣款/退款成功。
     * 异步受理（SUBMITTED）且终态回执未达时拒绝提交——保证「支付成功 ⇔ 渠道已扣款」，
     * 协调器留 CONFIRMING 由补偿/补单在回执到达后续驱。
     */
    public void capture(ChannelTccContext ctx, String type) {
        String to = "REFUND".equals(type) ? "REFUNDED" : "CAPTURED";
        ChannelTradeDO current = channelTradeMapper.selectByBizNo(ctx.getBizNo());
        if (current != null && "SUBMITTED".equals(current.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "渠道异步回执未达，不可提交: " + ctx.getBizNo());
        }
        channelTradeMapper.casStatus(ctx.getBizNo(), "PREAUTH", to);
        log.info("渠道{}(TCC Confirm) bizNo={}", "REFUND".equals(type) ? "退款" : "扣款", ctx.getBizNo());
    }

    /** TCC Cancel：撤销预授权/异步受理（均已终态时 CAS 影响 0 行，幂等 noop）。 */
    public void voidAuth(ChannelTccContext ctx) {
        int updated = channelTradeMapper.casStatus(ctx.getBizNo(), "PREAUTH", "VOIDED");
        if (updated == 0) {
            channelTradeMapper.casStatus(ctx.getBizNo(), "SUBMITTED", "VOIDED");
        }
        log.info("渠道撤销预授权(TCC Cancel) bizNo={}", ctx.getBizNo());
    }

    /** 退款沿用原扣款渠道（历史无渠道码行归属默认模拟银行），不做择优切换。 */
    private String resolveOrigChannel(String origBizNo) {
        if (origBizNo != null && !origBizNo.isBlank()) {
            ChannelTradeDO orig = channelTradeMapper.selectByBizNo(origBizNo);
            if (orig != null && orig.getChannelCode() != null && !orig.getChannelCode().isBlank()) {
                return orig.getChannelCode();
            }
        }
        return ruleConfig.defaultChannel();
    }

    private ChannelPayRequest payRequest(ChannelTccContext ctx, String channelCode) {
        ChannelPayRequest request = new ChannelPayRequest();
        request.setBizNo(ctx.getBizNo());
        request.setChannelCode(channelCode);
        request.setCardNo(ctx.getCardNo());
        request.setAmount(ctx.getAmount());
        request.setCardBin(cardBin(ctx.getCardNo()));
        return request;
    }

    private ChannelRefundRequest refundRequest(ChannelTccContext ctx, String channelCode) {
        ChannelRefundRequest request = new ChannelRefundRequest();
        request.setBizNo(ctx.getBizNo());
        request.setOrigBizNo(ctx.getOrigBizNo());
        request.setChannelCode(channelCode);
        request.setCardNo(ctx.getCardNo());
        request.setAmount(ctx.getAmount());
        return request;
    }

    private static String cardBin(String cardNo) {
        if (cardNo == null) {
            return null;
        }
        String digits = cardNo.replaceAll("\\D", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }

    public static ChannelTradeDTO toDTO(ChannelTradeDO trade) {
        ChannelTradeDTO dto = new ChannelTradeDTO();
        BeanUtils.copyProperties(trade, dto);
        return dto;
    }
}