package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-3 名单命中结果。 */
@Data
public class ListHitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean hit;
    private String listType;
    private String valueType;
    private String value;
    private String action;
    private String reason;
    private String entryId;
}