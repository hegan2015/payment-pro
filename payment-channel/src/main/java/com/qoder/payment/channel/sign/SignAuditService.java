package com.qoder.payment.channel.sign;

import com.qoder.payment.channel.dataobject.SignAuditDO;
import com.qoder.payment.channel.mapper.SignAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CH-6/X-4 授权审计写入（只追加）。
 *
 * <p><b>拒绝留痕分级</b>：受理前拒绝（授权/额度/有效期/扣款日/账户状态）不落任何扣款单与资金单，
 * 但必须留 {@code REJECT} 审计痕可查证；业务失败（渠道拒绝/风控拒绝）落 FAILED 扣款单 + 审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignAuditService {

    private final SignAuditMapper auditMapper;

    public void write(String signNo, String deductNo, String action, String operator, String requestId,
                      Long amount, String result, String reason) {
        SignAuditDO audit = new SignAuditDO();
        audit.setSignNo(signNo);
        audit.setDeductNo(deductNo);
        audit.setAction(action);
        audit.setOperator(operator);
        audit.setRequestId(requestId);
        audit.setAmount(amount);
        audit.setResult(result);
        audit.setReason(truncate(reason, 500));
        audit.setCreatedAt(System.currentTimeMillis());
        auditMapper.insert(audit);
    }

    /** 拒绝痕写入失败不掩盖原始拒绝（原始异常照常抛出），只记 ERROR 告警。 */
    public void writeQuietly(String signNo, String deductNo, String action, String operator, String requestId,
                             Long amount, String result, String reason) {
        try {
            write(signNo, deductNo, action, operator, requestId, amount, result, reason);
        } catch (Exception e) {
            log.error("[告警] 签约审计留痕失败 signNo={} action={}: {}", signNo, action, e.getMessage());
        }
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}