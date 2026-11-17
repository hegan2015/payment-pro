package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 通用过账请求：bizNo 幂等，lines 借贷必相等。 */
@Data
public class PostRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    private String subject;
    private String refId;
    private List<TransferLineDTO> lines;
}