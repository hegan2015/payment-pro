package com.qoder.payment.dtx;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.tcc.TccBranchRequest;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TCC 协调器（XTS 风格）。
 *
 * <p>全局事务日志 dtx_transaction + 分支日志 dtx_branch 落协调器本地库；
 * 一阶段同步执行，二阶段异步驱动，失败由 {@link #recover()} 按指数退避补偿，
 * 重试耗尽置 DEAD 等待人工/对账介入。终态与 {@link TccCompletionListener}
 * 回调在同一本地事务内提交，支撑事务性 outbox。
 */
@Slf4j
public class TccCoordinator {

    private static final int[] BACKOFF_SECONDS = {5, 10, 20, 40, 80, 160, 320, 640};
    private static final int MAX_RETRY = 8;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, TccParticipantFacade> participants;
    private final TccCompletionListener listener;

    public TccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          Map<String, TccParticipantFacade> participants, TccCompletionListener listener) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.participants = participants;
        this.listener = listener;
    }

    /** 开启全局事务；biz_type+biz_no 唯一约束兜底防重。 */
    public String begin(String bizType, String bizNo) {
        String xid = "T" + UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO dtx_transaction(xid,biz_type,biz_no,status,retry_count,next_retry_at,version,created_at,updated_at) "
                            + "VALUES(?,?,?,?,0,0,0,?,?)",
                    xid, bizType, bizNo, GlobalStatus.TRYING.name(), now, now);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "分布式事务已存在: " + bizType + "/" + bizNo);
        }
        return xid;
    }

    public String registerBranch(String xid, String participant, String action, Object context) {
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM dtx_branch WHERE xid=?", Integer.class, xid);
        String branchId = xid + "-B" + seq;
        jdbc.update("INSERT INTO dtx_branch(xid,branch_id,participant,action,status,context,seq) VALUES(?,?,?,?,?,?,?)",
                xid, branchId, participant, action, BranchStatus.INIT.name(), TccContextUtil.toJson(context), seq);
        return branchId;
    }

    /** 一阶段：按注册顺序执行 Try；任一失败即整体回滚并抛出业务异常。 */
    public void tryAll(String xid) {
        String failReason = null;
        for (BranchRow b : branches(xid)) {
            try {
                participant(b).tryAction(request(b)).orThrow();
                updateBranch(b, BranchStatus.TRY_OK);
            } catch (Exception e) {
                updateBranch(b, BranchStatus.TRY_FAIL);
                failReason = e.getMessage();
                log.warn("TCC Try 失败 xid={} branch={} participant={}: {}", xid, b.branchId(), b.participant(), failReason);
                break;
            }
        }
        if (failReason != null) {
            jdbc.update("UPDATE dtx_transaction SET status=?, fail_reason=?, updated_at=? WHERE xid=?",
                    GlobalStatus.CANCELLING.name(), truncate(failReason), System.currentTimeMillis(), xid);
            driveCancel(xid);
            throw new BizException(ResultCode.BIZ_ERROR, failReason);
        }
    }

    /** 二阶段提交：置 CONFIRMING 后立即尝试，失败留给补偿调度。 */
    public void commitAsync(String xid) {
        updateGlobal(xid, GlobalStatus.CONFIRMING);
        driveConfirm(xid);
        bumpRetryIfPending(xid);
    }

    public void rollbackAsync(String xid, String reason) {
        jdbc.update("UPDATE dtx_transaction SET status=?, fail_reason=?, updated_at=? WHERE xid=?",
                GlobalStatus.CANCELLING.name(), truncate(reason), System.currentTimeMillis(), xid);
        driveCancel(xid);
        bumpRetryIfPending(xid);
    }

    /** 补偿调度入口：扫描到期的非终态事务继续驱动，指数退避，耗尽置 DEAD。 */
    public void recover() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT xid, status FROM dtx_transaction WHERE status IN ('TRYING','CONFIRMING','CANCELLING') AND next_retry_at <= ?",
                System.currentTimeMillis());
        for (Map<String, Object> row : due) {
            String xid = (String) row.get("xid");
            GlobalStatus status = GlobalStatus.valueOf((String) row.get("status"));
            try {
                switch (status) {
                    case CONFIRMING -> driveConfirm(xid);
                    case CANCELLING -> driveCancel(xid);
                    case TRYING -> {
                        // 协调器在一阶段崩溃：全部 Try 成功则提交，否则回滚
                        List<BranchRow> bs = branches(xid);
                        boolean allOk = !bs.isEmpty() && bs.stream().allMatch(b -> b.status() == BranchStatus.TRY_OK);
                        if (allOk) {
                            commitAsync(xid);
                        } else {
                            jdbc.update("UPDATE dtx_transaction SET status=?, fail_reason=?, updated_at=? WHERE xid=?",
                                    GlobalStatus.CANCELLING.name(), "一阶段未完成，恢复回滚", System.currentTimeMillis(), xid);
                            driveCancel(xid);
                        }
                    }
                    default -> { }
                }
            } catch (Exception e) {
                log.warn("TCC 补偿驱动异常 xid={}: {}", xid, e.getMessage());
            }
            bumpRetryIfPending(xid);
        }
    }

    public GlobalStatus status(String xid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM dtx_transaction WHERE xid=?", xid);
        return rows.isEmpty() ? null : GlobalStatus.valueOf((String) rows.get(0).get("status"));
    }

    private void driveConfirm(String xid) {
        for (BranchRow b : branches(xid)) {
            if (b.status() == BranchStatus.CONFIRMED) {
                continue;
            }
            if (b.status() != BranchStatus.TRY_OK) {
                throw new BizException(ResultCode.BIZ_ERROR, "提交阶段存在未就绪分支: " + b.branchId() + "/" + b.status());
            }
            try {
                participant(b).confirm(request(b)).orThrow();
                updateBranch(b, BranchStatus.CONFIRMED);
            } catch (Exception e) {
                log.warn("TCC Confirm 待补偿 xid={} branch={}: {}", xid, b.branchId(), e.getMessage());
                return;
            }
        }
        tx.executeWithoutResult(s -> {
            updateGlobal(xid, GlobalStatus.CONFIRMED);
            if (listener != null) {
                listener.onConfirmed(xid);
            }
        });
        log.info("TCC 事务已提交 xid={}", xid);
    }

    private void driveCancel(String xid) {
        for (BranchRow b : branches(xid)) {
            if (b.status() == BranchStatus.CANCELLED) {
                continue;
            }
            try {
                // INIT/TRY_FAIL 分支同样调用 Cancel：参与者侧空回滚保证安全
                participant(b).cancel(request(b)).orThrow();
                updateBranch(b, BranchStatus.CANCELLED);
            } catch (Exception e) {
                log.warn("TCC Cancel 待补偿 xid={} branch={}: {}", xid, b.branchId(), e.getMessage());
                return;
            }
        }
        tx.executeWithoutResult(s -> {
            updateGlobal(xid, GlobalStatus.CANCELLED);
            if (listener != null) {
                listener.onCancelled(xid, failReason(xid));
            }
        });
        log.info("TCC 事务已回滚 xid={}", xid);
    }

    private void bumpRetryIfPending(String xid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, retry_count FROM dtx_transaction WHERE xid=?", xid);
        if (rows.isEmpty()) {
            return;
        }
        GlobalStatus status = GlobalStatus.valueOf((String) rows.get(0).get("status"));
        if (status == GlobalStatus.CONFIRMED || status == GlobalStatus.CANCELLED || status == GlobalStatus.DEAD) {
            return;
        }
        int count = ((Number) rows.get(0).get("retry_count")).intValue() + 1;
        long now = System.currentTimeMillis();
        if (count >= MAX_RETRY) {
            jdbc.update("UPDATE dtx_transaction SET status='DEAD', retry_count=?, updated_at=? WHERE xid=?", count, now, xid);
            log.error("TCC 补偿重试耗尽，置 DEAD 待人工介入 xid={} retry={}", xid, count);
            return;
        }
        long delay = BACKOFF_SECONDS[Math.min(count - 1, BACKOFF_SECONDS.length - 1)] * 1000L;
        jdbc.update("UPDATE dtx_transaction SET retry_count=?, next_retry_at=?, updated_at=? WHERE xid=?", count, now + delay, now, xid);
    }

    private List<BranchRow> branches(String xid) {
        return jdbc.query("SELECT xid, branch_id, participant, action, status, context, seq FROM dtx_branch WHERE xid=? ORDER BY seq",
                (rs, i) -> new BranchRow(rs.getString("xid"), rs.getString("branch_id"), rs.getString("participant"),
                        rs.getString("action"), BranchStatus.valueOf(rs.getString("status")), rs.getString("context"), rs.getInt("seq")),
                xid);
    }

    private TccBranchRequest request(BranchRow b) {
        TccBranchRequest req = new TccBranchRequest();
        req.setXid(b.xid());
        req.setBranchId(b.branchId());
        req.setAction(b.action());
        req.setContext(b.context());
        return req;
    }

    private TccParticipantFacade participant(BranchRow b) {
        TccParticipantFacade facade = participants.get(b.participant());
        if (facade == null) {
            throw new IllegalStateException("未注册的 TCC 参与者: " + b.participant());
        }
        return facade;
    }

    private void updateBranch(BranchRow b, BranchStatus status) {
        jdbc.update("UPDATE dtx_branch SET status=? WHERE xid=? AND branch_id=?", status.name(), b.xid(), b.branchId());
    }

    private void updateGlobal(String xid, GlobalStatus status) {
        jdbc.update("UPDATE dtx_transaction SET status=?, version=version+1, updated_at=? WHERE xid=?",
                status.name(), System.currentTimeMillis(), xid);
    }

    private String failReason(String xid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT fail_reason FROM dtx_transaction WHERE xid=?", xid);
        return rows.isEmpty() ? null : (String) rows.get(0).get("fail_reason");
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 256 ? s : s.substring(0, 256);
    }

    private record BranchRow(String xid, String branchId, String participant, String action,
                             BranchStatus status, String context, int seq) {
    }
}