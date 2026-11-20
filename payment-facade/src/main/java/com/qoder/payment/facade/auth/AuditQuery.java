package com.qoder.payment.facade.auth;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 审计分页查询（ADMIN/AUDITOR）。 */
@Data
public class AuditQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int page = 1;
    private int size = 20;
    private String username;
    private String action;
    private String path;
}