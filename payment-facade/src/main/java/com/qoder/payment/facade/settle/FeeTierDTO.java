package com.qoder.payment.facade.settle;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** ST-2 阶梯费率区间：[min, max) 分，命中取 bps；max 为空表示无上界。 */
@Data
public class FeeTierDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long min;
    private Long max;
    private Integer bps;
}