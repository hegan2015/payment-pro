package com.qoder.payment.member.service;

import com.qoder.payment.facade.auth.AuditLogDTO;
import com.qoder.payment.facade.auth.AuditQuery;
import com.qoder.payment.facade.auth.AuditWriteRequest;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.member.dataobject.AuditLogDO;
import com.qoder.payment.member.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/** 操作审计（G-1）：只追加；分页查询 ADMIN/AUDITOR（网关侧鉴权）。 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public String write(AuditWriteRequest request) {
        AuditLogDO log = new AuditLogDO();
        BeanUtils.copyProperties(request, log);
        log.setId("AL" + auditLogMapper.nextId());
        log.setCreatedAt(System.currentTimeMillis());
        if (log.getAction() == null || log.getAction().isBlank()) {
            log.setAction(log.getMethod() == null ? "UNKNOWN" : log.getMethod());
        }
        if (log.getPath() != null && log.getPath().length() > 256) {
            log.setPath(log.getPath().substring(0, 256));
        }
        if (log.getBizNo() != null && log.getBizNo().length() > 64) {
            log.setBizNo(log.getBizNo().substring(0, 64));
        }
        auditLogMapper.insert(log);
        return log.getId();
    }

    public PageDTO<AuditLogDTO> page(AuditQuery query) {
        int page = Math.max(1, query.getPage());
        int size = Math.min(200, Math.max(1, query.getSize()));
        long total = auditLogMapper.countPage(query.getUsername(), query.getAction(), query.getPath());
        List<AuditLogDTO> items = auditLogMapper
                .selectPage(query.getUsername(), query.getAction(), query.getPath(), size, (page - 1) * size)
                .stream().map(log -> {
                    AuditLogDTO dto = new AuditLogDTO();
                    BeanUtils.copyProperties(log, dto);
                    return dto;
                }).toList();
        return PageDTO.of(items, page, size, total);
    }
}