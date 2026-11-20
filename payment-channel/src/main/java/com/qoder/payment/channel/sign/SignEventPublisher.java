package com.qoder.payment.channel.sign;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.channel.dataobject.SignContractDO;
import com.qoder.payment.channel.dataobject.SignEventDO;
import com.qoder.payment.channel.mapper.SignContractMapper;
import com.qoder.payment.channel.mapper.SignEventMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import com.qoder.payment.facade.notify.NotifyRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CH-6 签约事件事务 outbox + 投递（SIGN_SUCCESS / SIGN_CANCEL，N-3 已登记事件名）。
 *
 * <p><b>可靠性（服务端兜底）</b>：事件行与协议状态变更<b>同一本地事务</b>写入 sign_event
 * （UNIQUE(event,biz_id)），提交后立即投递一次；notify 不可达 → 留 PENDING/FAILED + lastError，
 * 由 {@code signEventDispatch} 作业按退避重试，绝不丢事件、绝不依赖前端补偿。
 *
 * <p><b>只调 notify 域既有 {@link NotifyFacade#send}</b>（不给 NotifyFacade 新增方法）：
 * notify 域 {@code (NOTIFY, event:bizId)} 幂等吸收重复投递，新事件走统一 envelope
 * {@code {event,bizId,timestamp,payload}}，legacy PAY_SUCCESS/REFUND_SUCCESS body 一字未改（§0.7）。
 *
 * <p><b>扣款成功事件</b>复用既有 {@code PAY_SUCCESS}（payment 域 outbox 按支付单投递），
 * 本域不新造未登记事件名，避免污染通知契约。
 */
@Slf4j
@Component
public class SignEventPublisher {

    public static final String SIGN_SUCCESS = "SIGN_SUCCESS";
    public static final String SIGN_CANCEL = "SIGN_CANCEL";

    private final SignEventMapper eventMapper;
    private final SignContractMapper contractMapper;
    private final DynamicConfig config;
    private final ObjectMapper json = new ObjectMapper();

    @DubboReference(check = false, timeout = 8000, retries = 0)
    private NotifyFacade notifyFacade;

    public SignEventPublisher(SignEventMapper eventMapper, SignContractMapper contractMapper, DynamicConfig config) {
        this.eventMapper = eventMapper;
        this.contractMapper = contractMapper;
        this.config = config;
    }

    /** 事务内登记（幂等：同 event+bizId 已存在则跳过，绝不重复登记）。 */
    public void enqueue(String event, String signNo, String deductNo, Map<String, Object> payload) {
        if (eventMapper.selectByEventBiz(event, signNo) != null) {
            return;
        }
        SignEventDO row = new SignEventDO();
        row.setEvent(event);
        row.setBizId(signNo);
        row.setSignNo(signNo);
        row.setDeductNo(deductNo);
        row.setPayload(toJson(payload));
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setCreatedAt(System.currentTimeMillis());
        row.setNextRetryAt(0L);
        eventMapper.insert(row);
    }

    /** 事务提交后投递本协议的待发行（失败不抛，留痕交作业兜底）。 */
    public void dispatchSign(String signNo) {
        for (SignEventDO row : eventMapper.selectBySignNo(signNo)) {
            if ("SENT".equals(row.getStatus())) {
                continue;
            }
            deliver(row);
        }
    }

    /** 作业入口：投递到期的 PENDING/FAILED 行，返回成功笔数。 */
    public int dispatchDue(int limit) {
        int sent = 0;
        List<SignEventDO> due = eventMapper.selectDispatchDue(System.currentTimeMillis(), Math.max(1, limit));
        for (SignEventDO row : due) {
            if (deliver(row)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliver(SignEventDO row) {
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        long now = System.currentTimeMillis();
        try {
            SignContractDO contract = contractMapper.selectBySignNo(row.getSignNo());
            NotifyRequest request = new NotifyRequest();
            request.setEvent(row.getEvent());
            request.setBizId(row.getBizId());
            request.setPayload(fromJson(row.getPayload()));
            if (contract != null) {
                request.setCallbackUrl(contract.getCallbackUrl());
                request.setAppId(contract.getAppId());
            }
            var result = notifyFacade.send(request);
            if (result != null && result.isSuccess() && result.getData() != null) {
                NotifyRecordDTO record = result.getData();
                eventMapper.markSent(row.getId(), record.getId(), now, attempts);
                log.info("CH-6 签约事件投递成功 event={} bizId={} notifyId={} status={} attempts={}",
                        row.getEvent(), row.getBizId(), record.getId(), record.getStatus(), attempts);
                return true;
            }
            String error = result == null ? "notify 无响应" : result.getCode() + ":" + result.getMessage();
            fail(row, attempts, error, now);
            return false;
        } catch (Exception e) {
            fail(row, attempts, e.getClass().getSimpleName() + ": " + e.getMessage(), now);
            return false;
        }
    }

    private void fail(SignEventDO row, int attempts, String error, long now) {
        int max = config.getInt(SignConfigKeys.EVENT_MAX_RETRY, SignConfigKeys.DEFAULT_EVENT_MAX_RETRY);
        long interval = config.getLong(SignConfigKeys.EVENT_RETRY_INTERVAL_MS,
                SignConfigKeys.DEFAULT_EVENT_RETRY_INTERVAL_MS);
        String status = attempts >= max ? "FAILED" : "PENDING";
        eventMapper.markFailed(row.getId(), status, attempts, SignAuditService.truncate(error, 500),
                now + interval * attempts);
        log.warn("[告警] CH-6 签约事件投递未成功 event={} bizId={} attempts={} status={} error={}",
                row.getEvent(), row.getBizId(), attempts, status, error);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("签约事件 payload 序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("签约事件 payload 解析失败", e);
        }
    }
}