package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TransferDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private List<TransferLineDTO> lines;
    private String subject;
    private String refId;
    private Boolean reversed;
    private Long createdAt;
}