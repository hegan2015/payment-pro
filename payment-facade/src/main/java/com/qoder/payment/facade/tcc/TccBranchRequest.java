package com.qoder.payment.facade.tcc;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * TCC 分支请求。xid/branchId 由协调器生成，
 * context 为业务上下文 JSON，参与者自行反序列化。
 */
@Data
public class TccBranchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String xid;
    private String branchId;
    private String action;
    private String context;
}