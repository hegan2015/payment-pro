package com.qoder.payment.facade.cashier;

import com.qoder.payment.facade.trade.OrderDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class CashierDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private OrderDTO order;
    private List<PayMethodDTO> methods;
}