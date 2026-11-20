package com.qoder.payment.gateway.web;

import com.qoder.payment.facade.auth.AuditQuery;
import com.qoder.payment.facade.auth.ConsoleFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作审计查询（G-1）：GET /api/audit 分页，audit:read（ADMIN/AUDITOR）。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    @DubboReference
    private ConsoleFacade consoleFacade;

    @GetMapping
    public ResponseEntity<Object> page(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String username,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String path) {
        AuditQuery query = new AuditQuery();
        query.setPage(page);
        query.setSize(size);
        query.setUsername(username);
        query.setAction(action);
        query.setPath(path);
        return GatewaySupport.of(consoleFacade.auditPage(query));
    }
}