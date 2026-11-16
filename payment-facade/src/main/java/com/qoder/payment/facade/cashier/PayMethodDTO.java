package com.qoder.payment.facade.cashier;

import com.qoder.payment.facade.account.AccountDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PayMethodDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String method;
    private String name;
    private String hint;
    private List<AccountDTO> accounts;
}