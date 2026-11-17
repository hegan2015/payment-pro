package com.qoder.payment.notify.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import com.qoder.payment.notify.mapper.NotifyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyRecordMapper notifyRecordMapper;
    private final IdempotentTemplate idempotentTemplate;

    public NotifyRecordDTO send(NotifyRequest request) {
        String idempotentKey = request.getEvent() + ":" + request.getBizId();
        return idempotentTemplate.execute("NOTIFY", idempotentKey, NotifyRecordDTO.class, () -> {
            boolean pull = request.getCallbackUrl() == null || request.getCallbackUrl().isBlank();
            NotifyRecordDO record = new NotifyRecordDO();
            record.setId("N" + notifyRecordMapper.nextId());
            record.setEvent(request.getEvent());
            record.setBizId(request.getBizId());
            record.setPayload(TccContextUtil.toJson(request.getPayload() == null ? Map.of() : request.getPayload()));
            record.setCallbackUrl(request.getCallbackUrl());
            record.setStatus(pull ? "PULL" : "PENDING");
            record.setAttempts(0L);
            record.setNextRetryAt(0L);
            record.setCreatedAt(System.currentTimeMillis());
            notifyRecordMapper.insert(record);
            log.info("通知登记 notifyId={} event={} bizId={} mode={}", record.getId(), record.getEvent(), record.getBizId(), record.getStatus());
            return toDTO(record);
        });
    }

    public List<NotifyRecordDTO> records(String bizId) {
        List<NotifyRecordDO> records = bizId == null || bizId.isBlank()
                ? notifyRecordMapper.selectAll()
                : notifyRecordMapper.selectByBizId(bizId);
        return records.stream().map(NotifyService::toDTO).toList();
    }

    public NotifyRecordDTO get(String notifyId) {
        NotifyRecordDO record = notifyRecordMapper.selectById(notifyId);
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "通知不存在: " + notifyId);
        }
        return toDTO(record);
    }

    @SuppressWarnings("unchecked")
    static NotifyRecordDTO toDTO(NotifyRecordDO source) {
        NotifyRecordDTO dto = new NotifyRecordDTO();
        dto.setId(source.getId());
        dto.setEvent(source.getEvent());
        dto.setBizId(source.getBizId());
        dto.setPayload(TccContextUtil.fromJson(source.getPayload(), Map.class));
        dto.setCallbackUrl(source.getCallbackUrl());
        dto.setStatus(source.getStatus());
        dto.setAttempts(source.getAttempts());
        dto.setNextRetryAt(source.getNextRetryAt());
        dto.setLastError(source.getLastError());
        dto.setCreatedAt(source.getCreatedAt());
        return dto;
    }
}