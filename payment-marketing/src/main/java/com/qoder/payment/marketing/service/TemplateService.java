package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.MarketingTemplateDTO;
import com.qoder.payment.marketing.mapper.MarketingClaimMapper;
import com.qoder.payment.marketing.mapper.MarketingTemplateMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3a 营销模板（券/红包共用）。模板是发放/领取的口径快照来源；
 * 库存 {@code issued} 只经原子条件 UPDATE 累加（并发不超发），已发放数<b>只增不减</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    public static final String KIND_COUPON = "COUPON";
    public static final String KIND_RED_PACKET = "RED_PACKET";

    private final MarketingTemplateMapper templateMapper;
    private final MarketingClaimMapper claimMapper;
    private final MarketingSupport support;
    private final IdempotentTemplate idempotentTemplate;

    public MarketingTemplateDTO save(MarketingTemplateDTO template) {
        MarketingSupport.require(template != null, "模板必填");
        MarketingSupport.require(KIND_COUPON.equals(template.getKind()) || KIND_RED_PACKET.equals(template.getKind()),
                "kind 必须为 COUPON 或 RED_PACKET");
        MarketingSupport.require(MarketingSupport.notBlank(template.getName()), "name 必填");
        MarketingSupport.require(template.getAmount() != null && template.getAmount() > 0, "amount 必须为正整数（分）");
        long threshold = MarketingSupport.nz(template.getThreshold());
        MarketingSupport.require(threshold >= 0, "threshold 不得为负");
        String status = MarketingSupport.notBlank(template.getStatus()) ? template.getStatus() : "ACTIVE";
        MarketingSupport.require("ACTIVE".equals(status) || "DISABLED".equals(status), "status 必须为 ACTIVE/DISABLED");
        long now = System.currentTimeMillis();
        String key = MarketingSupport.notBlank(template.getId()) ? template.getId() : template.getName();
        MarketingSupport.requireKey(key);
        return idempotentTemplate.execute("TEMPLATE_SAVE", key, MarketingTemplateDTO.class, () -> {
            MarketingTemplateDTO existing = MarketingSupport.notBlank(template.getId())
                    ? templateMapper.selectById(template.getId()) : null;
            if (existing != null) {
                existing.setName(template.getName());
                existing.setAmount(template.getAmount());
                existing.setThreshold(threshold);
                existing.setTotalStock(MarketingSupport.nz(template.getTotalStock()));
                existing.setPerMemberLimit(MarketingSupport.nz(template.getPerMemberLimit()));
                existing.setValidDays(template.getValidDays());
                existing.setExpireAt(template.getExpireAt());
                existing.setStatus(status);
                existing.setRule(template.getRule());
                existing.setOperator(template.getOperator());
                existing.setUpdatedAt(now);
                templateMapper.update(existing);
                support.audit("TEMPLATE", existing.getId(), "UPDATE", null, status, template.getAmount(),
                        existing.getId(), template.getOperator(), "模板更新（issued 只增不减，保持 " + existing.getIssued() + "）");
                return templateMapper.selectById(existing.getId());
            }
            MarketingTemplateDTO row = new MarketingTemplateDTO();
            row.setId(MarketingSupport.notBlank(template.getId()) ? template.getId() : support.newId("TP"));
            row.setKind(template.getKind());
            row.setName(template.getName());
            row.setAmount(template.getAmount());
            row.setThreshold(threshold);
            row.setTotalStock(MarketingSupport.nz(template.getTotalStock()));
            row.setIssued(0);
            row.setPerMemberLimit(MarketingSupport.nz(template.getPerMemberLimit()));
            row.setValidDays(template.getValidDays());
            row.setExpireAt(template.getExpireAt());
            row.setStatus(status);
            row.setRule(template.getRule());
            row.setOperator(template.getOperator());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            templateMapper.insert(row);
            support.audit("TEMPLATE", row.getId(), "CREATE", null, status, row.getAmount(), row.getId(),
                    template.getOperator(), "模板新建 kind=" + row.getKind() + " stock=" + row.getTotalStock());
            return row;
        });
    }

    public MarketingTemplateDTO get(String templateId) {
        MarketingTemplateDTO template = templateMapper.selectById(templateId);
        MarketingSupport.requireFound(template, "模板不存在: " + templateId);
        return template;
    }

    public List<MarketingTemplateDTO> list(String kind, String status) {
        return templateMapper.selectList(kind, status);
    }

    /** 领取/发放共用：校验模板可用 + 库存原子抢占 + 每人限领。 */
    MarketingTemplateDTO consume(String templateId, String memberId, String expectedKind, boolean checkLimit) {
        MarketingTemplateDTO template = get(templateId);
        if (!expectedKind.equals(template.getKind())) {
            throw new BizException(ResultCode.PARAM_ERROR, "模板类型不匹配，期望 " + expectedKind);
        }
        if (!"ACTIVE".equals(template.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "模板已停用: " + templateId);
        }
        if (template.getExpireAt() != null && template.getExpireAt() <= System.currentTimeMillis()) {
            throw new BizException(ResultCode.BIZ_ERROR, "模板已过期: " + templateId);
        }
        int limit = MarketingSupport.nz(template.getPerMemberLimit());
        if (checkLimit && limit > 0) {
            int claimed = claimCount(templateId, memberId);
            if (claimed >= limit) {
                throw new BizException(ResultCode.BIZ_ERROR, "超过每人限领张数 " + limit + "（已领 " + claimed + "）");
            }
        }
        int rows = templateMapper.consumeStock(templateId, 1, System.currentTimeMillis());
        if (rows != 1) {
            throw new BizException(ResultCode.BIZ_ERROR, "模板库存不足或状态已变更: " + templateId);
        }
        return template;
    }

    private int claimCount(String templateId, String memberId) {
        return claimMapper.countByMember(templateId, memberId);
    }

    /** 有效期解析：绝对过期时间优先，其次 validDays，最后模板/域默认。 */
    long resolveExpireAt(Long expireAt, Integer validDays, MarketingTemplateDTO template) {
        if (expireAt != null && expireAt > 0) {
            return expireAt;
        }
        if (template != null && template.getExpireAt() != null && template.getExpireAt() > 0) {
            return template.getExpireAt();
        }
        int days = validDays != null && validDays > 0 ? validDays
                : (template != null && template.getValidDays() != null && template.getValidDays() > 0
                ? template.getValidDays() : support.voucherValidDays());
        return MarketingSupport.plusDays(System.currentTimeMillis(), days);
    }
}