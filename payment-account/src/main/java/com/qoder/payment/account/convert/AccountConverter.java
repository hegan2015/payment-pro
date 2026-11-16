package com.qoder.payment.account.convert;

import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.facade.account.AccountDTO;
import org.springframework.beans.BeanUtils;

public final class AccountConverter {

    private AccountConverter() {
    }

    public static AccountDTO toDTO(AccountDO source, long balance) {
        if (source == null) {
            return null;
        }
        AccountDTO dto = new AccountDTO();
        BeanUtils.copyProperties(source, dto);
        dto.setBalance(balance);
        return dto;
    }
}