package com.qoder.payment.facade.auth;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;

/** 控制台操作审计（G-1）：只追加写入 + 分页查询（ADMIN/AUDITOR）。 */
public interface ConsoleFacade {

    /** 追加一条审计，返回审计 ID。审计失败不得影响业务（调用方兜底）。 */
    Result<String> audit(AuditWriteRequest request);

    Result<PageDTO<AuditLogDTO>> auditPage(AuditQuery query);
}