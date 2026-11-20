package com.qoder.payment.facade.dispute;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 差错单分页查询条件（均可选）。 */
@Data
public class DisputeQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String status;
    private String type;
    private String source;
    private String bizNo;
    private int page = 1;
    private int size = 20;
}