package com.qoder.payment.settle.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.accounting.AccountingFacade;
import com.qoder.payment.facade.accounting.PostRequest;
import com.qoder.payment.facade.accounting.TransferLineDTO;
import com.qoder.payment.facade.settle.SettleRecordDTO;
import com.qoder.payment.facade.settle.ShareSettleDTO;
import com.qoder.payment.facade.settle.ShareSettleRequest;
import com.qoder.payment.facade.settle.ShareSummaryDTO;
import com.qoder.payment.settle.dataobject.SettleRecordDO;
import com.qoder.payment.settle.mapper.SettleRecordMapper;
import com.qoder.payment.settle.mapper.SettleShareMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ST-3 分账（<b>结算侧</b>）：登记分账结算明细 + 复式记账动账 + 净额守恒护栏。
 *
 * <p><b>与 P-3 的边界</b>：比例/固定额的<b>计算与编排</b>（分账单 share_order、多方拆分、
 * 异步投递 PP_SHARE topic、失败重试编排）属 payment 域 P-3；本域只做「收到一笔分账结果 →
 * 校验守恒 → 登记明细 → 复式记账划转」。本域不创建 share_order、不决定分账比例。
 *
 * <p><b>幂等</b>：登记 {@code (SETTLE_SHARE, shareId:receiver)} / {@code (SETTLE_SHARE_BACK, ...)}；
 * 账务 bizNo 由 (shareId,receiver) <b>确定性派生</b>（{@code SS:}/{@code SB:} 前缀），
 * 即便结算侧本地事务回滚后重试，账务侧也只会动账一次。
 *
 * <p><b>守恒</b>：{@code Σ SHARE.amount − Σ SHARE_BACK.amount ≤ 原支付结算单净额}，
 * 超额直接拒绝（绝不动账）；{@code shareSummary.diff == 0} 即完全分账、误差 0。
 *
 * <p>分账明细<b>不写入 settle_record</b>：保持 P0 结算单 PAY/REFUND 口径与 summary 恒等式
 * （net = gross − refund − fee）逐字节不变，ride 手续费对账与 I-5a 分片断言不受影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleShareService {

    public static final String TYPE_SHARE = "SHARE";
    public static final String TYPE_BACK = "SHARE_BACK";

    private final SettleShareMapper shareMapper;
    private final SettleRecordMapper settleRecordMapper;
    private final SettleSupport support;
    private final IdempotentTemplate idempotentTemplate;

    @DubboReference
    private AccountingFacade accountingFacade;

    @DubboReference
    private AccountFacade accountFacade;

    public ShareSettleDTO recordShare(ShareSettleRequest request) {
        return record(request, TYPE_SHARE);
    }

    public ShareSettleDTO recordShareBack(ShareSettleRequest request) {
        return record(request, TYPE_BACK);
    }

    private ShareSettleDTO record(ShareSettleRequest request, String type) {
        require(request != null && notBlank(request.getShareId()), "shareId 必填");
        require(notBlank(request.getPaymentId()), "paymentId 必填");
        require(notBlank(request.getReceiverAccountId()), "receiverAccountId 必填");
        require(request.getAmount() != null && request.getAmount() > 0, "amount 必须为正整数（分）");
        long amount = request.getAmount();
        long fee = request.getFee() == null ? 0L : request.getFee();
        require(fee >= 0 && fee <= amount, "fee 需在 0~amount");

        SettleRecordDO pay = support.readYourWrite(
                () -> settleRecordMapper.selectByBiz(request.getPaymentId(), "PAY"));
        if (pay == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "原支付结算单缺失: " + request.getPaymentId());
        }
        String merchantId = notBlank(request.getMerchantId()) ? request.getMerchantId() : pay.getMerchantId();
        if (!merchantId.equals(pay.getMerchantId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "分账商户与原结算单不一致");
        }
        String merchantAccountId = notBlank(request.getMerchantAccountId())
                ? request.getMerchantAccountId() : resolveMerchantAccount(merchantId);
        String receiver = request.getReceiverAccountId();
        require(!receiver.equals(merchantAccountId), "收款账户不得与出账账户相同");

        long settleNet = SettleSupport.nz(pay.getNet());
        long shareAmount = SettleSupport.nz(shareMapper.sumAmount(request.getShareId(), TYPE_SHARE));
        long backAmount = SettleSupport.nz(shareMapper.sumAmount(request.getShareId(), TYPE_BACK));
        long allocated = shareAmount - backAmount;
        ShareSettleDTO replay = shareMapper.selectByKey(request.getShareId(), receiver, type);
        if (replay == null) {
            if (TYPE_SHARE.equals(type)) {
                require(allocated + amount <= settleNet,
                        "分账超额：已分账 " + allocated + " + 本次 " + amount + " > 结算净额 " + settleNet);
            } else {
                ShareSettleDTO origin = shareMapper.selectByKey(request.getShareId(), receiver, TYPE_SHARE);
                require(origin != null, "回退无对应正向分账明细");
                long alreadyBack = SettleSupport.nz(shareMapper.sumAmount(request.getShareId(), TYPE_BACK));
                require(alreadyBack + amount <= SettleSupport.nz(origin.getAmount()),
                        "回退超额：已回退 " + alreadyBack + " + 本次 " + amount + " > 原分账 " + origin.getAmount());
            }
        }

        String bizNo = (TYPE_SHARE.equals(type) ? "SS:" : "SB:") + request.getShareId() + ":" + receiver;
        require(bizNo.length() <= 64, "分账幂等键过长（shareId+receiver 需 ≤ 60 字符）");
        String idemType = TYPE_SHARE.equals(type) ? "SETTLE_SHARE" : "SETTLE_SHARE_BACK";
        return idempotentTemplate.execute(idemType, request.getShareId() + ":" + receiver,
                ShareSettleDTO.class, () -> {
                    ShareSettleDTO existing = shareMapper.selectByKey(request.getShareId(), receiver, type);
                    if (existing != null) {
                        return existing;
                    }
                    // 先动账（账务 bizNo 确定性幂等）再登记：任一步失败重试都不会重复划转
                    PostRequest post = new PostRequest();
                    post.setBizNo(bizNo);
                    post.setSubject(TYPE_SHARE.equals(type) ? "分账结算" : "分账回退");
                    post.setRefId(request.getPaymentId());
                    post.setLines(List.of(
                            line(TYPE_SHARE.equals(type) ? merchantAccountId : receiver, "D", amount),
                            line(TYPE_SHARE.equals(type) ? receiver : merchantAccountId, "C", amount)));
                    accountingFacade.post(post).orThrow();

                    ShareSettleDTO detail = new ShareSettleDTO();
                    detail.setId(support.newId("D"));
                    detail.setShareId(request.getShareId());
                    detail.setPaymentId(request.getPaymentId());
                    detail.setMerchantId(merchantId);
                    detail.setMerchantAccountId(merchantAccountId);
                    detail.setReceiverAccountId(receiver);
                    detail.setAmount(amount);
                    detail.setFee(fee);
                    detail.setNet(amount - fee);
                    detail.setType(type);
                    detail.setStatus("SETTLED");
                    detail.setBizNo(bizNo);
                    detail.setCreatedAt(System.currentTimeMillis());
                    shareMapper.insert(detail);
                    log.info("ST-3 分账登记 type={} shareId={} receiver={} amount={} fee={} net={} bizNo={}",
                            type, detail.getShareId(), receiver, amount, fee, detail.getNet(), bizNo);
                    return detail;
                });
    }

    public List<ShareSettleDTO> shares(String shareId) {
        require(notBlank(shareId), "shareId 必填");
        return support.readYourWrite(() -> shareMapper.selectByShare(shareId));
    }

    public ShareSummaryDTO shareSummary(String shareId) {
        List<ShareSettleDTO> details = shares(shareId);
        ShareSummaryDTO dto = new ShareSummaryDTO();
        dto.setShareId(shareId);
        dto.setDetailCount(details.size());
        long shareAmount = 0;
        long shareFee = 0;
        long shareNet = 0;
        long backAmount = 0;
        long backNet = 0;
        for (ShareSettleDTO detail : details) {
            if (dto.getPaymentId() == null) {
                dto.setPaymentId(detail.getPaymentId());
                dto.setMerchantId(detail.getMerchantId());
            }
            if (TYPE_SHARE.equals(detail.getType())) {
                shareAmount += SettleSupport.nz(detail.getAmount());
                shareFee += SettleSupport.nz(detail.getFee());
                shareNet += SettleSupport.nz(detail.getNet());
            } else {
                backAmount += SettleSupport.nz(detail.getAmount());
                backNet += SettleSupport.nz(detail.getNet());
            }
        }
        long settleNet = 0;
        if (dto.getPaymentId() != null) {
            SettleRecordDO pay = support.readYourWrite(
                    () -> settleRecordMapper.selectByBiz(dto.getPaymentId(), "PAY"));
            settleNet = pay == null ? 0 : SettleSupport.nz(pay.getNet());
        }
        long allocated = shareAmount - backAmount;
        dto.setSettleNet(settleNet);
        dto.setShareAmount(shareAmount);
        dto.setShareFee(shareFee);
        dto.setShareNet(shareNet);
        dto.setBackAmount(backAmount);
        dto.setBackNet(backNet);
        dto.setAllocated(allocated);
        dto.setDiff(settleNet - allocated);
        dto.setBalanced(settleNet == allocated);
        return dto;
    }

    /** 窗口内分账净额（ST-5 账单 share 口径）。 */
    public long shareNetOf(String merchantId, long from, long to) {
        List<ShareSettleDTO> details = support.readYourWrite(() -> shareMapper.selectRange(merchantId, from, to));
        long net = 0;
        for (ShareSettleDTO detail : details) {
            net += TYPE_SHARE.equals(detail.getType())
                    ? SettleSupport.nz(detail.getAmount()) : -SettleSupport.nz(detail.getAmount());
        }
        return net;
    }

    public List<ShareSettleDTO> range(String merchantId, long from, long to) {
        return support.readYourWrite(() -> shareMapper.selectRange(merchantId, from, to));
    }

    private String resolveMerchantAccount(String merchantId) {
        AccountDTO account = accountFacade.getByMember(merchantId).orThrow();
        if (account == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "商户结算账户缺失: " + merchantId);
        }
        return account.getId();
    }

    private static TransferLineDTO line(String accountId, String dir, long amount) {
        TransferLineDTO line = new TransferLineDTO();
        line.setAccountId(accountId);
        line.setDir(dir);
        line.setAmount(amount);
        return line;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ResultCode.PARAM_ERROR, message);
        }
    }
}