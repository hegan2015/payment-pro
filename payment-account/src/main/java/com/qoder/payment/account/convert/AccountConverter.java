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
        // P1 additive 字段归一（既有行迁移前/后口径一致）
        if (dto.getAccountType() == null || dto.getAccountType().isBlank()) {
            dto.setAccountType("BALANCE");
        }
        if (dto.getLevel() == null || dto.getLevel().isBlank()) {
            dto.setLevel("I");
        }
        if (dto.getFreezeType() == null || dto.getFreezeType().isBlank()) {
            dto.setFreezeType("NONE");
        }
        // A-4 additive：既有账户/存量行缺省 CNY（余额与所有既有字段口径不变）
        if (dto.getCurrency() == null || dto.getCurrency().isBlank()) {
            dto.setCurrency("CNY");
        }
        // A-5 additive：既有账户/存量行缺省 ACTIVE（生命周期不影响任何既有字段口径）
        if (dto.getLifecycleStatus() == null || dto.getLifecycleStatus().isBlank()) {
            dto.setLifecycleStatus("ACTIVE");
        }
        return dto;
    }
}