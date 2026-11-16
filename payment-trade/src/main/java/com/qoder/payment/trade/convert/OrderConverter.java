package com.qoder.payment.trade.convert;

import com.qoder.payment.facade.trade.OrderDTO;
import com.qoder.payment.trade.dataobject.OrderDO;
import org.springframework.beans.BeanUtils;

public final class OrderConverter {

    private OrderConverter() {
    }

    public static OrderDTO toDTO(OrderDO source) {
        if (source == null) {
            return null;
        }
        OrderDTO dto = new OrderDTO();
        BeanUtils.copyProperties(source, dto);
        return dto;
    }
}