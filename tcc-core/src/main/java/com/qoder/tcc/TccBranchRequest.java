package com.qoder.tcc;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * TCC 分支请求（传输无关的通用 DTO）。xid/branchId 由协调器生成，
 * context 为业务上下文 JSON，参与者自行反序列化。
 *
 * <p>跨进程传输时由接入方适配层转换为自身 RPC 契约 DTO（如 Dubbo facade DTO）。
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