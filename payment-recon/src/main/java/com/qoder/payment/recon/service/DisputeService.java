package com.qoder.payment.recon.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.common.PageDTO;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.dispute.DisputeFixLogDTO;
import com.qoder.payment.facade.dispute.DisputeQuery;
import com.qoder.payment.facade.dispute.DisputeStatsDTO;
import com.qoder.payment.recon.dataobject.DisputeFixLogDO;
import com.qoder.payment.recon.dataobject.DisputeOrderDO;
import com.qoder.payment.recon.mapper.DisputeFixLogMapper;
import com.qoder.payment.recon.mapper.DisputeOrderMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * X-1 差错处理平台核心：登记（幂等去重）→ 状态机迁移（全部 CAS + fix_log 审计）→ 核销/关闭。
 *
 * <p>状态机：OPEN→ASSIGNED（assign）→FIXING（approve/autofix）→RESOLVED→CLOSED；
 * OPEN/ASSIGNED→IGNORED（reject）；RESOLVED/IGNORED→CLOSED；
 * 对账复核通过/通知重放送达 OPEN/ASSIGNED/FIXING→RESOLVED（AUTO_RESOLVE 留痕）。
 * 修复执行见 {@link DisputeAutoFixer}（S-4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    public static final String OPEN = "OPEN";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String FIXING = "FIXING";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";
    public static final String IGNORED = "IGNORED";

    private static final Set<String> SOURCES = Set.of("RECON", "NOTIFY", "PAYMENT", "RESERVE");

    private final DisputeOrderMapper disputeOrderMapper;
    private final DisputeFixLogMapper disputeFixLogMapper;
    private final IdempotentTemplate idempotentTemplate;

    // ---------- 登记 ----------

    /** 幂等登记：(DISPUTE, source[:checkName][:bizDate]:bizNo)；未关闭同来源单直接返回（重复对账不重复建单）。 */
    public DisputeDTO create(DisputeCreateRequest request) {
        if (request.getType() == null || request.getType().isBlank()
                || request.getSource() == null || !SOURCES.contains(request.getSource())
                || request.getBizNo() == null || request.getBizNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "type/source(RECON|NOTIFY|PAYMENT|RESERVE)/bizNo 必填");
        }
        DisputeOrderDO existing = disputeOrderMapper.selectOpenBySourceBiz(
                request.getSource(), request.getBizNo(), request.getCheckName());
        if (existing != null) {
            return toDTO(existing, false);
        }
        String strategy = request.getStrategy() == null || request.getStrategy().isBlank()
                ? DisputeClassifier.defaultStrategy(request.getType()) : request.getStrategy();
        String key = idempotentKey(request);
        return idempotentTemplate.execute("DISPUTE", key, DisputeDTO.class, () -> {
            // 并发兜底：占位成功后再查一次未关闭单
            DisputeOrderDO dup = disputeOrderMapper.selectOpenBySourceBiz(
                    request.getSource(), request.getBizNo(), request.getCheckName());
            if (dup != null) {
                return toDTO(dup, false);
            }
            long now = System.currentTimeMillis();
            long id = disputeOrderMapper.nextId();
            DisputeOrderDO order = new DisputeOrderDO();
            order.setId(id);
            order.setDisputeNo("DSP" + id);
            order.setType(request.getType());
            order.setSource(request.getSource());
            order.setBizNo(request.getBizNo());
            order.setPaymentId(request.getPaymentId());
            order.setAmount(request.getAmount() == null ? 0L : request.getAmount());
            order.setStatus(OPEN);
            order.setStrategy(strategy);
            order.setReason(truncate(request.getReason(), 512));
            order.setCheckName(request.getCheckName());
            order.setBizDate(request.getBizDate());
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            disputeOrderMapper.insert(order);
            writeLog(id, "CREATE", "system", null, OPEN,
                    "source=" + request.getSource() + " check=" + request.getCheckName() + " strategy=" + strategy);
            // 注意：事件名不可用 dispute_created——Prometheus counter 会额外暴露 _created 序列，
            // 与既有 payment_recon_dispute 计数器的 payment_recon_dispute_created 采集名冲突。
            countQuietly("dispute_new", "type", request.getType(), "source", request.getSource());
            log.warn("[告警] 新差错单 disputeNo={} type={} source={} bizNo={} amount={} strategy={}",
                    order.getDisputeNo(), order.getType(), order.getSource(), order.getBizNo(), order.getAmount(), strategy);
            return toDTO(order, false);
        });
    }

    private static String idempotentKey(DisputeCreateRequest request) {
        StringBuilder sb = new StringBuilder(request.getSource());
        if (request.getCheckName() != null && !request.getCheckName().isBlank()) {
            sb.append(':').append(request.getCheckName());
        }
        if (request.getBizDate() != null && !request.getBizDate().isBlank()) {
            sb.append(':').append(request.getBizDate());
        }
        sb.append(':').append(request.getBizNo());
        String key = sb.toString();
        // idempotent_record.biz_no VARCHAR(64)：超长压缩为前缀+哈希，保持确定性
        return key.length() <= 64 ? key : key.substring(0, 40) + "~" + Integer.toHexString(key.hashCode());
    }

    // ---------- 查询 ----------

    public PageDTO<DisputeDTO> list(DisputeQuery query) {
        int page = Math.max(1, query.getPage());
        int size = Math.min(200, Math.max(1, query.getSize()));
        List<DisputeDTO> items = disputeOrderMapper
                .selectPage(query.getStatus(), query.getType(), query.getSource(), query.getBizNo(), (page - 1) * size, size)
                .stream().map(d -> toDTO(d, false)).toList();
        long total = disputeOrderMapper.countPage(query.getStatus(), query.getType(), query.getSource(), query.getBizNo());
        return PageDTO.of(items, page, size, total);
    }

    public DisputeDTO get(long disputeId) {
        return toDTO(require(disputeId), true);
    }

    public DisputeStatsDTO stats() {
        DisputeStatsDTO dto = new DisputeStatsDTO();
        dto.setTotal(disputeOrderMapper.countAll());
        dto.setByStatus(group(disputeOrderMapper.countByStatus()));
        dto.setByType(group(disputeOrderMapper.countByType()));
        dto.setBySource(group(disputeOrderMapper.countBySource()));
        dto.setOpen(dto.getByStatus().getOrDefault(OPEN, 0L)
                + dto.getByStatus().getOrDefault(ASSIGNED, 0L)
                + dto.getByStatus().getOrDefault(FIXING, 0L));
        return dto;
    }

    private static Map<String, Long> group(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object k = row.get("k");
            Object c = row.get("c");
            if (k != null && c instanceof Number n) {
                result.put(k.toString(), n.longValue());
            }
        }
        return result;
    }

    // ---------- 状态机（人工动作，全部审计） ----------

    public DisputeDTO assign(long disputeId, String assignee, String operator) {
        if (assignee == null || assignee.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "assignee 必填");
        }
        DisputeOrderDO order = require(disputeId);
        requireStatus(order, OPEN, ASSIGNED);
        disputeOrderMapper.updateAssignee(disputeId, assignee, System.currentTimeMillis());
        transition(disputeId, order.getStatus(), ASSIGNED, "ASSIGN", operator, "assignee=" + assignee);
        return get(disputeId);
    }

    public DisputeDTO reject(long disputeId, String operator, String reason) {
        DisputeOrderDO order = require(disputeId);
        requireStatus(order, OPEN, ASSIGNED);
        transition(disputeId, order.getStatus(), IGNORED, "REJECT", operator, reason);
        return get(disputeId);
    }

    public DisputeDTO resolve(long disputeId, String operator, String resolution) {
        DisputeOrderDO order = require(disputeId);
        requireStatus(order, OPEN, ASSIGNED, FIXING);
        markResolved(order, order.getStatus(), "RESOLVE", operator, resolution, order.getFixBizNo());
        return get(disputeId);
    }

    public DisputeDTO close(long disputeId, String operator) {
        DisputeOrderDO order = require(disputeId);
        requireStatus(order, RESOLVED, IGNORED);
        transition(disputeId, order.getStatus(), CLOSED, "CLOSE", operator, null);
        return get(disputeId);
    }

    /** N-2 回调：通知重放送达后按来源+业务号自动核销（无匹配单返回 null）。 */
    public DisputeDTO resolveBySourceBiz(String source, String bizNo, String operator, String detail) {
        DisputeOrderDO order = disputeOrderMapper.selectOpenBySourceBiz(source, bizNo, null);
        if (order == null) {
            return null;
        }
        markResolved(order, order.getStatus(), "AUTO_RESOLVE", operator, detail, order.getFixBizNo());
        return get(order.getId());
    }

    /** RC-1：对账复核通过 → 该核对项下未关闭差错单自动 RESOLVED（留痕），返回核销 id。 */
    public List<Long> autoResolveByCheck(String checkName, String detail) {
        List<Long> resolved = new ArrayList<>();
        for (DisputeOrderDO order : disputeOrderMapper.selectOpenByCheck(checkName)) {
            markResolved(order, order.getStatus(), "AUTO_RESOLVE", "recon", detail, order.getFixBizNo());
            resolved.add(order.getId());
            countQuietly("dispute_auto_resolved", "check", checkName);
        }
        return resolved;
    }

    // ---------- 内部 ----------

    /** 指标埋点绝不阻断业务（注册冲突等异常静默降级为日志）。 */
    static void countQuietly(String event, String... tags) {
        try {
            MetricsSupport.count(event, tags);
        } catch (RuntimeException e) {
            log.warn("指标埋点失败（忽略） event={} err={}", event, e.getMessage());
        }
    }

    DisputeOrderDO require(long disputeId) {
        DisputeOrderDO order = disputeOrderMapper.selectById(disputeId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "差错单不存在: " + disputeId);
        }
        return order;
    }

    private static void requireStatus(DisputeOrderDO order, String... allowed) {
        for (String s : allowed) {
            if (s.equals(order.getStatus())) {
                return;
            }
        }
        throw new BizException(ResultCode.CONFLICT,
                "差错单状态 " + order.getStatus() + " 不允许该操作（需 " + String.join("/", allowed) + "）");
    }

    /** CAS 迁移 + 审计；并发竞争失败抛 CONFLICT。 */
    void transition(long disputeId, String from, String to, String action, String operator, String detail) {
        int updated = disputeOrderMapper.casStatus(disputeId, from, to, System.currentTimeMillis());
        if (updated == 0) {
            throw new BizException(ResultCode.CONFLICT, "差错单状态已变更，请刷新后重试: " + disputeId);
        }
        writeLog(disputeId, action, operator, from, to, detail);
    }

    /** CAS 置 RESOLVED（带 resolved_at/fix_biz_no）+ 审计。 */
    void markResolved(DisputeOrderDO order, String from, String action, String operator, String detail, String fixBizNo) {
        long now = System.currentTimeMillis();
        int updated = disputeOrderMapper.casResolved(order.getId(), from, RESOLVED, fixBizNo, now);
        if (updated == 0) {
            throw new BizException(ResultCode.CONFLICT, "差错单状态已变更，请刷新后重试: " + order.getId());
        }
        writeLog(order.getId(), action, operator, from, RESOLVED, detail);
        log.info("差错单核销 disputeNo={} action={} operator={} fixBizNo={}", order.getDisputeNo(), action, operator, fixBizNo);
    }

    void writeLog(long disputeId, String action, String operator, String before, String after, String detail) {
        DisputeFixLogDO logDO = new DisputeFixLogDO();
        logDO.setId(disputeFixLogMapper.nextId());
        logDO.setDisputeId(disputeId);
        logDO.setAction(action);
        logDO.setOperator(operator == null || operator.isBlank() ? "system" : operator);
        logDO.setBeforeStatus(before);
        logDO.setAfterStatus(after);
        logDO.setDetail(truncate(detail, 1024));
        logDO.setCreatedAt(System.currentTimeMillis());
        disputeFixLogMapper.insert(logDO);
    }

    DisputeDTO toDTO(DisputeOrderDO source, boolean withLogs) {
        DisputeDTO dto = new DisputeDTO();
        dto.setId(source.getId());
        dto.setDisputeNo(source.getDisputeNo());
        dto.setType(source.getType());
        dto.setSource(source.getSource());
        dto.setBizNo(source.getBizNo());
        dto.setPaymentId(source.getPaymentId());
        dto.setAmount(source.getAmount());
        dto.setStatus(source.getStatus());
        dto.setStrategy(source.getStrategy());
        dto.setAssignee(source.getAssignee());
        dto.setReason(source.getReason());
        dto.setFixBizNo(source.getFixBizNo());
        dto.setCheckName(source.getCheckName());
        dto.setBizDate(source.getBizDate());
        dto.setCreatedAt(source.getCreatedAt());
        dto.setUpdatedAt(source.getUpdatedAt());
        dto.setResolvedAt(source.getResolvedAt());
        if (withLogs) {
            dto.setLogs(disputeFixLogMapper.selectByDispute(source.getId()).stream().map(l -> {
                DisputeFixLogDTO ld = new DisputeFixLogDTO();
                ld.setId(l.getId());
                ld.setDisputeId(l.getDisputeId());
                ld.setAction(l.getAction());
                ld.setOperator(l.getOperator());
                ld.setBeforeStatus(l.getBeforeStatus());
                ld.setAfterStatus(l.getAfterStatus());
                ld.setDetail(l.getDetail());
                ld.setCreatedAt(l.getCreatedAt());
                return ld;
            }).toList());
        }
        return dto;
    }

    private static String truncate(String v, int max) {
        return v == null ? null : (v.length() <= max ? v : v.substring(0, max));
    }
}