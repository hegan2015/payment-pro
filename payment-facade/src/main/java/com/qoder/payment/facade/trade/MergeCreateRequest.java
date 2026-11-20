package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** T-5 合并单创建请求：≥2 笔同商户 CREATED INSTANT 订单，requestId 幂等。 */
@Data
public class MergeCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<String> orderIds;
    private String requestId;
}