package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 红冲结果：反向凭证号与反向分录。 */
@Data
public class ReverseResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String reverseBizNo;
    private List<TransferLineDTO> lines;
}