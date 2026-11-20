package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 证书到期告警条目（M-1）。 */
@Data
public class CertAlertDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String memberId;
    private String certId;
    private String certSn;
    private Long notAfter;
    private Long daysLeft;
}