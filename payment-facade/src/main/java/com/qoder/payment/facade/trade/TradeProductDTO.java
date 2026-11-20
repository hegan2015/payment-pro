package com.qoder.payment.facade.trade;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * T-3 交易产品目录项（只读）：产品码、启用开关、状态机名、状态集合与 DynamicConfig 生效参数。
 * 供控制台/验证脚本断言「产品路由与参数可配、默认 INSTANT 行为不变」。
 */
@Data
public class TradeProductDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** INSTANT/ESCROW/PREAUTH。 */
    private String product;
    /** 是否启用（DynamicConfig trade.product.<code>.enabled，默认 true）。 */
    private Boolean enabled;
    /** 是否为缺省产品（未显式指定 productType 时路由到它）。 */
    private Boolean defaultProduct;
    /** 状态机实现类简名（SPI 路由落点）。 */
    private String stateMachine;
    /** 订单状态集合（产品各自状态机）。 */
    private List<String> states;
    /** 生效参数快照（DynamicConfig 键 → 值）。 */
    private Map<String, String> params;
    private String description;
}