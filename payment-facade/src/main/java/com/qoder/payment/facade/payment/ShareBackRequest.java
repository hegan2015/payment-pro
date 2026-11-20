package com.qoder.payment.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** P-3 分账回退请求：整单回退（反向划转），幂等键 (SHARE_BACK, shareId)，已回退拒绝重复。 */
@Data
public class ShareBackRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String shareId;
    private String requestId;
}