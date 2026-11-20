package com.qoder.payment.account.freeze;

import com.qoder.payment.account.dataobject.FreezeLogDO;
import com.qoder.payment.account.mapper.FreezeLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** A-3 冻结/解冻凭证留痕（只追加，不可篡改）。 */
@Service
@RequiredArgsConstructor
public class FreezeLogService {

    private final FreezeLogMapper freezeLogMapper;

    public void log(String accountId, String action, String freezeType, String reasonType, String reason,
                    Long amount, String operator, String authorizedBy, String bizNo) {
        FreezeLogDO log = new FreezeLogDO();
        log.setId("FL" + freezeLogMapper.nextId());
        log.setAccountId(accountId);
        log.setAction(action);
        log.setFreezeType(freezeType);
        log.setReasonType(reasonType);
        log.setReason(reason);
        log.setAmount(amount);
        log.setOperator(operator);
        log.setAuthorizedBy(authorizedBy);
        log.setBizNo(bizNo);
        log.setCreatedAt(System.currentTimeMillis());
        freezeLogMapper.insert(log);
    }

    public List<FreezeLogDO> list(String accountId) {
        return freezeLogMapper.selectByAccount(accountId);
    }
}