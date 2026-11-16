package com.qoder.payment.facade.account;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AccountStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String status;
    private String type;
}