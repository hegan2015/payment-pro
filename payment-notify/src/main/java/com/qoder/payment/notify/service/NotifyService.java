package com.qoder.payment.notify.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.IdempotentTemplate;
import com.qoder.tcc.TccContextUtil;
import com.qoder.payment.facade.notify.NotifyBatchResendDTO;
import com.qoder.payment.facade.notify.NotifyEvent;
import com.qoder.payment.facade.notify.NotifyPageDTO;
import com.qoder.payment.facade.notify.NotifyQueryRequest;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.notify.dataobject.NotifyRecordDO;
import com.qoder.payment.notify.mapper.NotifyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyRecordMapper notifyRecordMapper;
    private final IdempotentTemplate idempotentTemplate;

    public NotifyRecordDTO send(NotifyRequest request) {
        String idempotentKey = request.getEvent() + ":" + request.getBizId();
        NotifyEvent template = NotifyEvent.of(request.getEvent());
        // N-3 模板校验只作用于新事件；冻结事件（PAY_SUCCESS/REFUND_SUCCESS）与未登记事件保持既有宽松语义
        if (template != null && !template.isLegacy()) {
            Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
            for (String field : template.getRequiredFields()) {
                if (payload.get(field) == null) {
                    throw new BizException(ResultCode.PARAM_ERROR,
                            "事件 " + template.name() + " payload 缺少必填字段 " + field);
                }
            }
        }
        return idempotentTemplate.execute("NOTIFY", idempotentKey, NotifyRecordDTO.class, () -> {
            boolean pull = request.getCallbackUrl() == null || request.getCallbackUrl().isBlank();
            NotifyRecordDO record = new NotifyRecordDO();
            record.setId("N" + notifyRecordMapper.nextId());
            record.setEvent(request.getEvent());
            record.setBizId(request.getBizId());
            record.setPayload(TccContextUtil.toJson(bodyOf(request)));
            record.setCallbackUrl(request.getCallbackUrl());
            record.setStatus(pull ? "PULL" : "PENDING");
            record.setAttempts(0L);
            record.setNextRetryAt(0L);
            record.setCreatedAt(System.currentTimeMillis());
            record.setAppId(request.getAppId());
            record.setSignType(request.getSignType() == null || request.getSignType().isBlank()
                    ? NotifySigner.HMAC_SHA256 : request.getSignType());
            notifyRecordMapper.insert(record);
            log.info("通知登记 notifyId={} event={} bizId={} mode={} signType={}", record.getId(), record.getEvent(), record.getBizId(), record.getStatus(), record.getSignType());
            return toDTO(record);
        });
    }

    /**
     * N-3 通知体构造：
     * <ul>
     *   <li><b>冻结事件</b>（PAY_SUCCESS/REFUND_SUCCESS）与未登记事件 → {@code payload} 原样，
     *       序列化结果逐字节不变（ride-pro 回调留痕红线）；</li>
     *   <li><b>新事件</b> → 统一 envelope {@code {event,bizId,timestamp,payload}}（字段顺序固定），
     *       签名仍覆盖整个 body（NotifySigner 机制不变）。</li>
     * </ul>
     */
    static Map<String, Object> bodyOf(NotifyRequest request) {
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        NotifyEvent template = NotifyEvent.of(request.getEvent());
        if (template == null || template.isLegacy()) {
            return payload;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", template.name());
        envelope.put("bizId", request.getBizId());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("payload", payload);
        return envelope;
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

    /**
     * N-2 死信重放：FAILED（重试耗尽）重置 PENDING/attempts=0 重新进入推送队列（重新签名推送）。
     * 幂等：非 FAILED（PENDING/DELIVERED/PULL）直接返回当前记录，不重复重置。
     */
    public NotifyRecordDTO replay(String notifyId) {
        NotifyRecordDO record = notifyRecordMapper.selectById(notifyId);
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "通知不存在: " + notifyId);
        }
        int reset = notifyRecordMapper.resetForReplay(notifyId);
        if (reset > 0) {
            log.info("通知死信重放 notifyId={} bizId={} 原attempts={}", notifyId, record.getBizId(), record.getAttempts());
        }
        return toDTO(notifyRecordMapper.selectById(notifyId));
    }

    // ---------------- N-4 查询 / 重发（additive） ----------------

    /** N-4 分页查询（脱敏视图）：page 从 1 起，size 默认 20 上限 200。 */
    public NotifyPageDTO query(NotifyQueryRequest request) {
        NotifyQueryRequest q = request == null ? new NotifyQueryRequest() : request;
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null || q.getSize() < 1 ? 20 : Math.min(q.getSize(), 200);
        long total = notifyRecordMapper.countPage(q.getAppId(), q.getEvent(), q.getStatus(), q.getBizId(), q.getFrom(), q.getTo());
        List<NotifyRecordDTO> records = notifyRecordMapper
                .selectPage(q.getAppId(), q.getEvent(), q.getStatus(), q.getBizId(), q.getFrom(), q.getTo(), size, (page - 1) * size)
                .stream().map(NotifyService::toMaskedDTO).toList();
        NotifyPageDTO result = new NotifyPageDTO();
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setRecords(records);
        return result;
    }

    /**
     * N-4 单条重发：幂等 (NOTIFY_RESEND, notifyId:requestId)；CAS 只重置 FAILED/PULL，
     * DELIVERED/PENDING 跳过（不重复推送已送达/在途），不产生新记录。
     */
    public NotifyRecordDTO resend(String notifyId, String requestId) {
        NotifyRecordDO record = notifyRecordMapper.selectById(notifyId);
        if (record == null) {
            throw new BizException(ResultCode.NOT_FOUND, "通知不存在: " + notifyId);
        }
        String key = resendKey(notifyId, requestId);
        return idempotentTemplate.execute("NOTIFY_RESEND", key, NotifyRecordDTO.class, () -> {
            int reset = notifyRecordMapper.resetForResend(notifyId);
            if (reset > 0) {
                log.info("通知重发 notifyId={} bizId={} event={} 原status={} requestId={}",
                        notifyId, record.getBizId(), record.getEvent(), record.getStatus(), requestId);
            } else {
                log.info("通知重发跳过（已送达/在途，不重复推送） notifyId={} status={} requestId={}",
                        notifyId, record.getStatus(), requestId);
            }
            return toDTO(notifyRecordMapper.selectById(notifyId));
        });
    }

    /** N-4 批量重发：幂等 (NOTIFY_RESEND_BATCH, requestId)，逐条 CAS，上限 100 条。 */
    public NotifyBatchResendDTO resendBatch(List<String> notifyIds, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "批量重发 requestId 必填（幂等键）");
        }
        if (notifyIds == null || notifyIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "批量重发 notifyIds 不能为空");
        }
        if (notifyIds.size() > 100) {
            throw new BizException(ResultCode.PARAM_ERROR, "批量重发单批上限 100 条");
        }
        String batchKey = requestId.length() <= 64 ? requestId
                : UUID.nameUUIDFromBytes(requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return idempotentTemplate.execute("NOTIFY_RESEND_BATCH", batchKey, NotifyBatchResendDTO.class, () -> {
            List<NotifyRecordDTO> records = new ArrayList<>();
            int resent = 0;
            int skipped = 0;
            for (String notifyId : notifyIds) {
                NotifyRecordDO record = notifyRecordMapper.selectById(notifyId);
                if (record == null) {
                    throw new BizException(ResultCode.NOT_FOUND, "通知不存在: " + notifyId);
                }
                if (notifyRecordMapper.resetForResend(notifyId) > 0) {
                    resent++;
                } else {
                    skipped++;
                }
                records.add(toDTO(notifyRecordMapper.selectById(notifyId)));
            }
            NotifyBatchResendDTO result = new NotifyBatchResendDTO();
            result.setRequestId(requestId);
            result.setRequested(notifyIds.size());
            result.setResent(resent);
            result.setSkipped(skipped);
            result.setRecords(records);
            log.info("通知批量重发 requestId={} requested={} resent={} skipped={}", requestId, notifyIds.size(), resent, skipped);
            return result;
        });
    }

    /** N-3 事件目录（冻结标记 + payload 模板），供控制台与 API 文档展示。 */
    public List<Map<String, Object>> events() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (NotifyEvent event : NotifyEvent.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("event", event.name());
            item.put("description", event.getDescription());
            item.put("legacy", event.isLegacy());
            item.put("envelope", !event.isLegacy());
            item.put("requiredFields", event.getRequiredFields());
            catalog.add(item);
        }
        return catalog;
    }

    /** 幂等键长度护栏（idempotent_record.biz_no VARCHAR(64)）。 */
    private static String resendKey(String notifyId, String requestId) {
        String rid = requestId == null || requestId.isBlank() ? "default" : requestId.trim();
        String key = notifyId + ":" + rid;
        if (key.length() <= 64) {
            return key;
        }
        return notifyId + ":" + UUID.nameUUIDFromBytes(rid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * N-4 查询脱敏视图：密钥永不出现在 DTO（本就没有该字段），另外
     * 签名值只留 8 位预览 + 长度、callbackUrl 去查询串、lastError 去 URL 查询串。
     * 既有 {@code records/get} 口径不变（商户补查验签需要完整 signature）。
     */
    static NotifyRecordDTO toMaskedDTO(NotifyRecordDO source) {
        NotifyRecordDTO dto = toDTO(source);
        String signature = dto.getSignature();
        if (signature != null && !signature.isBlank()) {
            dto.setSignature(signature.substring(0, Math.min(8, signature.length())) + "****(len=" + signature.length() + ")");
        }
        dto.setCallbackUrl(stripQuery(dto.getCallbackUrl()));
        dto.setLastError(stripQuery(dto.getLastError()));
        return dto;
    }

    private static String stripQuery(String value) {
        if (value == null) {
            return null;
        }
        int q = value.indexOf('?');
        return q < 0 ? value : value.substring(0, q);
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
        dto.setSignType(source.getSignType());
        dto.setSignature(source.getSignature());
        dto.setNonce(source.getNonce());
        dto.setAppId(source.getAppId());
        dto.setResendCount(source.getResendCount() == null ? 0L : source.getResendCount());
        return dto;
    }
}