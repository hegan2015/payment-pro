package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.ClaimRequest;
import com.qoder.payment.facade.marketing.CouponUseDTO;
import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import com.qoder.payment.facade.marketing.RedPacketDTO;
import com.qoder.payment.facade.marketing.RedPacketIssueRequest;
import com.qoder.payment.facade.marketing.RedPacketUseRequest;
import com.qoder.payment.marketing.dataobject.MarketingClaimDO;
import com.qoder.payment.marketing.mapper.MarketingClaimMapper;
import com.qoder.payment.marketing.mapper.RedPacketMapper;
import com.qoder.payment.marketing.mapper.RedPacketUseMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3a 红包（无门槛现金券语义）：发放/领取/核销/过期/退回。
 *
 * <p>幂等键：发放 {@code (REDPACKET_ISSUE, bizNo)}、领取 {@code (CLAIM, templateId:memberId:bizNo)}、
 * 核销 {@code (REDPACKET_USE, redPacketId:paymentId)} + {@code red_packet_use} 双 UNIQUE + CAS、
 * 退回 {@code (REDPACKET_REFUND, useId)}。资金口径与券完全一致（D SYS_MARKETING / C 用户户）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedPacketService {

    public static final String SOURCE = "RED_PACKET";

    private final RedPacketMapper packetMapper;
    private final RedPacketUseMapper useMapper;
    private final MarketingClaimMapper claimMapper;
    private final TemplateService templateService;
    private final MarketingSupport support;
    private final MarketingFundService fundService;
    private final IdempotentTemplate idempotentTemplate;

    public RedPacketDTO issue(RedPacketIssueRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.requireKey(request.getBizNo());
        MarketingSupport.require(request.getBizNo().length() <= 61, "bizNo 需 ≤61 字符（派生 requestId 前缀 RI:）");
        String requestId = "RI:" + request.getBizNo();
        return idempotentTemplate.execute("REDPACKET_ISSUE", request.getBizNo(), RedPacketDTO.class, () -> {
            RedPacketDTO existing = packetMapper.selectByRequest(requestId);
            if (existing != null) {
                return existing;
            }
            MarketingTemplateDTO template = null;
            if (MarketingSupport.notBlank(request.getTemplateId())) {
                template = templateService.consume(request.getTemplateId(), request.getMemberId(),
                        TemplateService.KIND_RED_PACKET, false);
            }
            long amount = request.getAmount() != null ? request.getAmount()
                    : (template != null ? MarketingSupport.nz(template.getAmount()) : 0L);
            MarketingSupport.require(amount > 0, "红包面额必须为正整数（分）");
            long now = System.currentTimeMillis();
            RedPacketDTO packet = new RedPacketDTO();
            packet.setId(support.newId("RP"));
            packet.setMemberId(request.getMemberId());
            packet.setTemplateId(template == null ? null : template.getId());
            packet.setAmount(amount);
            packet.setStatus(CouponService.STATUS_AVAILABLE);
            packet.setExpireAt(templateService.resolveExpireAt(request.getExpireAt(), request.getValidDays(), template));
            packet.setRequestId(requestId);
            packet.setBizNo(request.getBizNo());
            packet.setOperator(request.getOperator());
            packet.setCreatedAt(now);
            packet.setUpdatedAt(now);
            packetMapper.insert(packet);
            support.audit("RED_PACKET", packet.getId(), "ISSUE", null, CouponService.STATUS_AVAILABLE, amount,
                    requestId, request.getOperator(), "发红包 member=" + packet.getMemberId());
            return packet;
        });
    }

    public RedPacketDTO claim(ClaimRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getTemplateId()), "templateId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.requireKey(request.getBizNo());
        String key = request.getTemplateId() + ":" + request.getMemberId() + ":" + request.getBizNo();
        MarketingSupport.requireKey(key);
        return idempotentTemplate.execute("CLAIM", key, RedPacketDTO.class, () -> {
            MarketingClaimDO existing = claimMapper.selectByKey(request.getTemplateId(), request.getMemberId(),
                    request.getBizNo());
            if (existing != null) {
                RedPacketDTO packet = packetMapper.selectById(existing.getVoucherId());
                MarketingSupport.requireFound(packet, "领取留痕对应红包缺失: " + existing.getVoucherId());
                return packet;
            }
            MarketingTemplateDTO template = templateService.consume(request.getTemplateId(), request.getMemberId(),
                    TemplateService.KIND_RED_PACKET, true);
            long now = System.currentTimeMillis();
            String claimId = support.newId("CL");
            RedPacketDTO packet = new RedPacketDTO();
            packet.setId(support.newId("RP"));
            packet.setMemberId(request.getMemberId());
            packet.setTemplateId(template.getId());
            packet.setAmount(template.getAmount());
            packet.setStatus(CouponService.STATUS_AVAILABLE);
            packet.setExpireAt(templateService.resolveExpireAt(null, null, template));
            packet.setRequestId("RC:" + claimId);
            packet.setBizNo(key);
            packet.setOperator(request.getOperator());
            packet.setCreatedAt(now);
            packet.setUpdatedAt(now);
            packetMapper.insert(packet);

            MarketingClaimDO claim = new MarketingClaimDO();
            claim.setId(claimId);
            claim.setTemplateId(template.getId());
            claim.setMemberId(request.getMemberId());
            claim.setKind(TemplateService.KIND_RED_PACKET);
            claim.setVoucherId(packet.getId());
            claim.setBizNo(request.getBizNo());
            claim.setCreatedAt(now);
            claimMapper.insert(claim);
            support.audit("RED_PACKET", packet.getId(), "CLAIM", null, CouponService.STATUS_AVAILABLE,
                    packet.getAmount(), key, request.getOperator(), "领红包 template=" + template.getId());
            return packet;
        });
    }

    public RedPacketDTO get(String redPacketId) {
        RedPacketDTO packet = packetMapper.selectById(redPacketId);
        MarketingSupport.requireFound(packet, "红包不存在: " + redPacketId);
        return packet;
    }

    public List<RedPacketDTO> list(String memberId, String status) {
        MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 必填");
        return packetMapper.selectByMember(memberId, status);
    }

    public List<RedPacketDTO> available(String memberId) {
        return packetMapper.selectAvailable(memberId, System.currentTimeMillis());
    }

    public CouponUseDTO use(RedPacketUseRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getRedPacketId()), "redPacketId 必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填");
        String key = request.getRedPacketId() + ":" + request.getPaymentId();
        MarketingSupport.requireKey(key);
        String requestId = MarketingSupport.notBlank(request.getRequestId()) ? request.getRequestId() : "RU:" + key;
        MarketingSupport.requireKey(requestId);
        String bizNo = MarketingSupport.FUND_PREFIX + "R:" + key;
        MarketingSupport.requireKey(bizNo);
        return idempotentTemplate.execute("REDPACKET_USE", key, CouponUseDTO.class, () -> {
            CouponUseDTO byRequest = useMapper.selectByRequest(requestId);
            if (byRequest != null) {
                return byRequest;
            }
            CouponUseDTO byPacket = useMapper.selectByRedPacket(request.getRedPacketId());
            if (byPacket != null) {
                if (byPacket.getPaymentId().equals(request.getPaymentId())) {
                    return byPacket;
                }
                throw new BizException(ResultCode.CONFLICT, "红包已核销于其它支付单: " + byPacket.getPaymentId());
            }
            RedPacketDTO packet = get(request.getRedPacketId());
            if (!CouponService.STATUS_AVAILABLE.equals(packet.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "红包状态不可核销: " + packet.getStatus());
            }
            long now = System.currentTimeMillis();
            if (packet.getExpireAt() <= now) {
                throw new BizException(ResultCode.BIZ_ERROR, "红包已过期，不可核销: " + packet.getId());
            }
            if (MarketingSupport.notBlank(request.getMemberId())
                    && !request.getMemberId().equals(packet.getMemberId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "红包归属会员与请求不一致");
            }
            long amount = request.getAmount() != null ? request.getAmount() : packet.getAmount();
            MarketingSupport.require(amount > 0 && amount <= packet.getAmount(),
                    "核销额需在 (0, 红包面额] 内：amount=" + amount + " face=" + packet.getAmount());
            if (request.getOrderAmount() != null) {
                long already = fundService.sourceNet(request.getPaymentId());
                MarketingSupport.require(already + amount <= request.getOrderAmount(),
                        "营销抵扣超订单额：已抵扣 " + already + " + 本次 " + amount + " > " + request.getOrderAmount());
            }
            String accountId = fundService.resolveAccount(packet.getMemberId(), request.getAccountId());
            String useId = support.newId("RU");
            int cas = packetMapper.casUse(packet.getId(), useId, now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "红包状态并发变更，核销未受理: " + packet.getId());
            }
            fundService.subsidy(bizNo, accountId, amount, "营销红包核销", request.getPaymentId());

            CouponUseDTO use = new CouponUseDTO();
            use.setId(useId);
            use.setKind(SOURCE);
            use.setVoucherId(packet.getId());
            use.setPaymentId(request.getPaymentId());
            use.setOrderId(request.getOrderId());
            use.setMemberId(packet.getMemberId());
            use.setAccountId(accountId);
            use.setAmount(amount);
            use.setBizNo(bizNo);
            use.setStatus(CouponService.STATUS_USED);
            use.setRequestId(requestId);
            use.setOperator(request.getOperator());
            use.setCreatedAt(now);
            use.setUpdatedAt(now);
            useMapper.insert(use);
            fundService.recordSource(request.getPaymentId(), SOURCE, useId, amount,
                    MarketingFundService.TYPE_USE, bizNo);
            support.audit("RED_PACKET", packet.getId(), "USE", CouponService.STATUS_AVAILABLE,
                    CouponService.STATUS_USED, amount, bizNo, request.getOperator(),
                    "核销 payment=" + request.getPaymentId() + " account=" + accountId);
            return use;
        });
    }

    public CouponUseDTO refund(String redPacketUseId) {
        fundService.requireEnabled();
        MarketingSupport.require(MarketingSupport.notBlank(redPacketUseId), "redPacketUseId 必填");
        MarketingSupport.requireKey(redPacketUseId);
        return idempotentTemplate.execute("REDPACKET_REFUND", redPacketUseId, CouponUseDTO.class, () -> {
            CouponUseDTO use = useMapper.selectById(redPacketUseId);
            MarketingSupport.requireFound(use, "核销单不存在: " + redPacketUseId);
            if (CouponService.STATUS_REFUNDED.equals(use.getStatus())) {
                return use;
            }
            if (!CouponService.STATUS_USED.equals(use.getStatus())) {
                throw new BizException(ResultCode.CONFLICT, "核销单状态不可退回: " + use.getStatus());
            }
            long now = System.currentTimeMillis();
            String bizNo = MarketingSupport.FUND_REFUND_PREFIX + "R:" + use.getVoucherId() + ":" + use.getPaymentId();
            MarketingSupport.requireKey(bizNo);
            int cas = useMapper.casRefund(redPacketUseId, now);
            if (cas != 1) {
                throw new BizException(ResultCode.CONFLICT, "核销单并发变更，退回未受理: " + redPacketUseId);
            }
            fundService.subsidyBack(bizNo, use.getAccountId(), use.getAmount(), "营销红包退回", use.getPaymentId());
            packetMapper.casRefund(use.getVoucherId(), now);
            fundService.recordSource(use.getPaymentId(), SOURCE, use.getId(), use.getAmount(),
                    MarketingFundService.TYPE_REFUND, bizNo);
            support.audit("RED_PACKET", use.getVoucherId(), "REFUND", CouponService.STATUS_USED,
                    CouponService.STATUS_REFUNDED, use.getAmount(), bizNo, use.getOperator(),
                    "退回核销单 " + redPacketUseId);
            return useMapper.selectById(redPacketUseId);
        });
    }

    public int expireDue() {
        long now = System.currentTimeMillis();
        int rows = packetMapper.expireDue(now);
        if (rows > 0) {
            support.audit("RED_PACKET", "BATCH", "EXPIRE", CouponService.STATUS_AVAILABLE,
                    CouponService.STATUS_EXPIRED, (long) rows, "EXPIRE:" + now, "SYSTEM",
                    "过期作业置 EXPIRED " + rows + " 个");
        }
        return rows;
    }
}