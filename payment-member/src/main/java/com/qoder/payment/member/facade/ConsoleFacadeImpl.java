package com.qoder.payment.member.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.auth.AuditLogDTO;
import com.qoder.payment.facade.auth.AuditQuery;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.auth.ConsoleFacade;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.member.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 操作审计（G-1），member 域实现（audit_log 落 pp_member）。 */
@DubboService
@RequiredArgsConstructor
public class ConsoleFacadeImpl implements ConsoleFacade {

    private final AuditService auditService;

    @Override
    public Result<String> audit(AuditWriteRequest request) {
        return FacadeSupport.execute(() -> auditService.write(request));
    }

    @Override
    public Result<PageDTO<AuditLogDTO>> auditPage(AuditQuery query) {
        return FacadeSupport.execute(() -> auditService.page(query));
    }
}