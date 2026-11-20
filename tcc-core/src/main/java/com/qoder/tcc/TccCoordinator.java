package com.qoder.tcc;

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
 *
 * <p><b>异步渠道增量（CH-1，向后兼容）</b>：同步调用路径 {@code begin → tryAll → commitAsync}
 * 行为完全不变；仅当调用方显式使用 {@link #beginAwaiting(String, String)}（或
 * {@link #awaitExternal(String)}）时，全局事务进入 {@link GlobalStatus#WAITING}，
 * 资金保持冻结直到外部终态到达，再由 {@link #resumeCommit(String)} /
 * {@link #resumeCancel(String, String)} 以状态 CAS 唯一驱动二阶段。
 * WAITING 不计入补偿重试次数（不会被误判 DEAD），一阶段未完成的 WAITING 由
 * {@link #recover()} 按空回滚语义回滚。
 */
@Slf4j
public class TccCoordinator {

    private static final int[] BACKOFF_SECONDS = {5, 10, 20, 40, 80, 160, 320, 640};
    private static final int MAX_RETRY = 8;
    /** 异步事务一阶段宽限期：避免 recover 与正在执行的 tryAll 竞态（同步路径不受影响）。 */
    private static final long TRY_GRACE_MS = 10_000L;
    /** WAITING 等待外部终态时的扫描间隔：只推迟扫描，不累加 retry_count。 */
    private static final long WAITING_POLL_MS = 30_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, TccParticipant> participants;
    private final TccCompletionListener listener;

    public TccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          Map<String, TccParticipant> participants, TccCompletionListener listener) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.participants = participants;
        this.listener = listener;
    }

    /** 开启全局事务；biz_type+biz_no 唯一约束兜底防重。 */
    public String begin(String bizType, String bizNo) {
        return insertGlobal(bizType, bizNo, GlobalStatus.TRYING, 0L);
    }

    /**
     * 开启「等待外部终态」的全局事务（异步渠道专用）：落库即 WAITING，
     * 一阶段成功后无需再翻状态，因此「tryAll 完成但尚未置 WAITING」的崩溃窗口不存在——
     * recover 绝不会把异步事务当作同步事务直接提交。
     *
     * <p>一阶段未完成即崩溃时，recover 按空回滚语义回滚（宽限期后生效）。
     */
    public String beginAwaiting(String bizType, String bizNo) {
        return insertGlobal(bizType, bizNo, GlobalStatus.WAITING, TRY_GRACE_MS);
    }

    private String insertGlobal(String bizType, String bizNo, GlobalStatus status, long retryDelayMs) {
        String xid = "T" + UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO dtx_transaction(xid,biz_type,biz_no,status,retry_count,next_retry_at,version,created_at,updated_at) "
                            + "VALUES(?,?,?,?,0,?,0,?,?)",
                    xid, bizType, bizNo, status.name(), now + retryDelayMs, now, now);
        } catch (DuplicateKeyException e) {
            throw new TccException(TccException.CONFLICT, "分布式事务已存在: " + bizType + "/" + bizNo);
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
                participant(b).tryAction(request(b));
                updateBranch(b, BranchStatus.TRY_OK);
            } catch (Exception e) {
                updateBranch(b, BranchStatus.TRY_FAIL);
                failReason = e.getMessage();
                log.warn("TCC Try 失败 xid={} branch={} participant={}: {}", xid, b.branchId(), b.participant(), failReason);
                break;
            }
        }
        if (failReason != null) {
            if (toCancelling(xid, failReason)) {
                driveCancel(xid);
            } else {
                log.error("TCC Try 失败但全局状态已不可回滚（疑似并发提交）xid={} status={}", xid, status(xid));
            }
            throw new TccException(TccException.BIZ_ERROR, failReason);
        }
    }

    /** 二阶段提交：CAS 置 CONFIRMING 后立即尝试，失败留给补偿调度；已不可提交则让位（绝不覆盖回滚态）。 */
    public void commitAsync(String xid) {
        if (!casGlobalIn(xid, GlobalStatus.CONFIRMING, null,
                GlobalStatus.TRYING, GlobalStatus.WAITING, GlobalStatus.CONFIRMING)) {
            log.warn("TCC 提交让位（全局状态已不可提交）xid={} status={}", xid, status(xid));
            return;
        }
        driveConfirm(xid);
        bumpRetryIfPending(xid);
    }

    public void rollbackAsync(String xid, String reason) {
        if (!toCancelling(xid, reason)) {
            log.warn("TCC 回滚让位（全局状态已不可回滚）xid={} status={}", xid, status(xid));
            return;
        }
        driveCancel(xid);
        bumpRetryIfPending(xid);
    }

    /**
     * 一阶段全部 TRY_OK 后转入等待外部终态（TRYING→WAITING，状态 CAS）。
     * 供已用 {@link #begin} 开启的事务显式声明异步；新接入建议直接用 {@link #beginAwaiting}。
     *
     * @return true 表示本次调用完成翻转；false 表示已不在 TRYING（重复调用幂等）
     */
    public boolean awaitExternal(String xid) {
        if (!allTryOk(xid)) {
            throw new TccException(TccException.BIZ_ERROR, "一阶段未全部成功，不可进入 WAITING: " + xid);
        }
        boolean flipped = casGlobal(xid, GlobalStatus.TRYING, GlobalStatus.WAITING);
        if (flipped) {
            postponeWaiting(xid);
        }
        return flipped;
    }

    /**
     * 外部终态「成功」回执到达：WAITING→CONFIRMING→驱动二阶段提交。
     * 状态 CAS 保证 WAITING→终态唯一：并发回执/补单只有一个驱动者，其余直接返回 false。
     *
     * @return true 表示本次调用取得驱动权；false 表示已被他者驱动或已终态（幂等无副作用）
     */
    public boolean resumeCommit(String xid) {
        if (!allTryOk(xid)) {
            // 一阶段未全部就绪绝不允许提交（否则先过账的分支无法回滚）；应走 resumeCancel
            log.warn("TCC resumeCommit 拒绝：一阶段未全部 TRY_OK xid={}", xid);
            return false;
        }
        if (!casGlobal(xid, GlobalStatus.WAITING, GlobalStatus.CONFIRMING)) {
            log.info("TCC resumeCommit 幂等跳过（非 WAITING）xid={} status={}", xid, status(xid));
            return false;
        }
        driveConfirm(xid);
        bumpRetryIfPending(xid);
        return true;
    }

    /**
     * 外部终态「失败」回执到达：WAITING→CANCELLING→驱动二阶段回滚（解冻/释放/撤销）。
     * 与 {@link #resumeCommit} 共用同一把状态 CAS，二者只可能有一个生效。
     */
    public boolean resumeCancel(String xid, String reason) {
        int updated = jdbc.update("UPDATE dtx_transaction SET status=?, fail_reason=?, version=version+1, updated_at=? "
                        + "WHERE xid=? AND status=?",
                GlobalStatus.CANCELLING.name(), truncate(reason), System.currentTimeMillis(), xid, GlobalStatus.WAITING.name());
        if (updated != 1) {
            log.info("TCC resumeCancel 幂等跳过（非 WAITING）xid={} status={}", xid, status(xid));
            return false;
        }
        driveCancel(xid);
        bumpRetryIfPending(xid);
        return true;
    }

    /** 一阶段是否全部 TRY_OK（分支非空且无 INIT/TRY_FAIL），补单决策与 recover 共用同一判据。 */
    public boolean allTryOk(String xid) {
        List<BranchRow> bs = branches(xid);
        return !bs.isEmpty() && bs.stream().allMatch(b -> b.status() == BranchStatus.TRY_OK);
    }

    /**
     * 单事务续驱入口（支付侧补单/手动收敛调用），决策与 {@link #recover()} 完全一致：
     * TRYING 全 TRY_OK→提交、否则回滚；CONFIRMING/CANCELLING→续驱；
     * WAITING 全 TRY_OK→不动作（等外部终态，由 resumeCommit/resumeCancel 驱动）；终态→不动作。
     */
    public void drive(String xid) {
        GlobalStatus status = status(xid);
        if (status == null || isTerminal(status)) {
            return;
        }
        if (status == GlobalStatus.WAITING && allTryOk(xid)) {
            postponeWaiting(xid);
            return;
        }
        try {
            driveOne(xid, status);
        } catch (Exception e) {
            log.warn("TCC 补单驱动异常 xid={}: {}", xid, e.getMessage());
        }
        bumpRetryIfPending(xid);
    }

    /** 补偿调度入口：扫描到期的非终态事务继续驱动，指数退避，耗尽置 DEAD。 */
    public void recover() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT xid, status FROM dtx_transaction WHERE status IN ('TRYING','WAITING','CONFIRMING','CANCELLING') AND next_retry_at <= ?",
                System.currentTimeMillis());
        for (Map<String, Object> row : due) {
            String xid = (String) row.get("xid");
            GlobalStatus status = GlobalStatus.valueOf((String) row.get("status"));
            // 异步事务等待外部终态：不驱动、不累加重试次数（否则长时间等待会被误判 DEAD）
            if (status == GlobalStatus.WAITING && allTryOk(xid)) {
                postponeWaiting(xid);
                continue;
            }
            try {
                driveOne(xid, status);
            } catch (Exception e) {
                log.warn("TCC 补偿驱动异常 xid={}: {}", xid, e.getMessage());
            }
            bumpRetryIfPending(xid);
        }
    }

    private void driveOne(String xid, GlobalStatus status) {
        switch (status) {
            case CONFIRMING -> driveConfirm(xid);
            case CANCELLING -> driveCancel(xid);
            case TRYING -> {
                // 协调器在一阶段崩溃：全部 Try 成功则提交，否则回滚
                if (allTryOk(xid)) {
                    commitAsync(xid);
                } else if (toCancelling(xid, "一阶段未完成，恢复回滚")) {
                    driveCancel(xid);
                }
            }
            // WAITING 且一阶段未完成（异步事务 Try 中途崩溃）：按空回滚语义回滚，绝不提交
            case WAITING -> {
                if (allTryOk(xid)) {
                    postponeWaiting(xid);
                } else if (toCancelling(xid, "一阶段未完成，恢复回滚")) {
                    driveCancel(xid);
                }
            }
            default -> { }
        }
    }

    private static boolean isTerminal(GlobalStatus status) {
        return status == GlobalStatus.CONFIRMED || status == GlobalStatus.CANCELLED || status == GlobalStatus.DEAD;
    }

    public GlobalStatus status(String xid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM dtx_transaction WHERE xid=?", xid);
        return rows.isEmpty() ? null : GlobalStatus.valueOf((String) rows.get(0).get("status"));
    }

    private void driveConfirm(String xid) {
        GlobalStatus cur = status(xid);
        if (cur == GlobalStatus.CONFIRMED) {
            return;
        }
        if (cur != GlobalStatus.CONFIRMING) {
            log.error("TCC 拒绝提交驱动（全局状态非 CONFIRMING，防与回滚竞态）xid={} status={}", xid, cur);
            return;
        }
        for (BranchRow b : branches(xid)) {
            if (b.status() == BranchStatus.CONFIRMED) {
                continue;
            }
            if (b.status() != BranchStatus.TRY_OK) {
                throw new TccException(TccException.BIZ_ERROR, "提交阶段存在未就绪分支: " + b.branchId() + "/" + b.status());
            }
            try {
                participant(b).confirm(request(b));
                updateBranch(b, BranchStatus.CONFIRMED);
            } catch (Exception e) {
                log.warn("TCC Confirm 待补偿 xid={} branch={}: {}", xid, b.branchId(), e.getMessage());
                return;
            }
        }
        tx.executeWithoutResult(s -> {
            if (!casGlobal(xid, GlobalStatus.CONFIRMING, GlobalStatus.CONFIRMED)) {
                log.error("TCC 提交终态 CAS 未生效（状态被并发改写，需人工核查）xid={} status={}", xid, status(xid));
                return;
            }
            if (listener != null) {
                listener.onConfirmed(xid);
            }
        });
        log.info("TCC 事务已提交 xid={}", xid);
    }

    private void driveCancel(String xid) {
        GlobalStatus cur = status(xid);
        if (cur == GlobalStatus.CANCELLED) {
            return;
        }
        if (cur != GlobalStatus.CANCELLING) {
            log.error("TCC 拒绝回滚驱动（全局状态非 CANCELLING，防与提交竞态）xid={} status={}", xid, cur);
            return;
        }
        for (BranchRow b : branches(xid)) {
            if (b.status() == BranchStatus.CANCELLED) {
                continue;
            }
            try {
                // INIT/TRY_FAIL 分支同样调用 Cancel：参与者侧空回滚保证安全
                participant(b).cancel(request(b));
                updateBranch(b, BranchStatus.CANCELLED);
            } catch (Exception e) {
                log.warn("TCC Cancel 待补偿 xid={} branch={}: {}", xid, b.branchId(), e.getMessage());
                return;
            }
        }
        tx.executeWithoutResult(s -> {
            if (!casGlobal(xid, GlobalStatus.CANCELLING, GlobalStatus.CANCELLED)) {
                log.error("TCC 回滚终态 CAS 未生效（状态被并发改写，需人工核查）xid={} status={}", xid, status(xid));
                return;
            }
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

    private TccParticipant participant(BranchRow b) {
        TccParticipant facade = participants.get(b.participant());
        if (facade == null) {
            throw new IllegalStateException("未注册的 TCC 参与者: " + b.participant());
        }
        return facade;
    }

    private void updateBranch(BranchRow b, BranchStatus status) {
        jdbc.update("UPDATE dtx_branch SET status=? WHERE xid=? AND branch_id=?", status.name(), b.xid(), b.branchId());
    }

    /** 全局状态 CAS（带 version 递增）：终态迁移唯一护栏，重复驱动只有首个生效。 */
    private boolean casGlobal(String xid, GlobalStatus from, GlobalStatus to) {
        return jdbc.update("UPDATE dtx_transaction SET status=?, version=version+1, updated_at=? WHERE xid=? AND status=?",
                to.name(), System.currentTimeMillis(), xid, from.name()) == 1;
    }

    /**
     * 全局状态互斥 CAS：只允许从指定源状态集合迁入目标态，终态不可被覆盖。
     *
     * <p>confirm 与 cancel 必须互斥——否则补偿线程读到 TRYING 且瞬时 allTryOk=false 就置 CANCELLING
     * 并回调 onCancelled（支付单 CAS 成 FAILED），而请求线程随后无守卫地覆盖成 CONFIRMING 并过账，
     * 造成「全局 CONFIRMED + 账务已过账 + 订单 PAID，但支付单 FAILED」的资金语义违规。
     */
    private boolean casGlobalIn(String xid, GlobalStatus to, String reason, GlobalStatus... allowedFrom) {
        StringBuilder sql = new StringBuilder(
                "UPDATE dtx_transaction SET status=?, version=version+1, updated_at=?");
        if (reason != null) {
            sql.append(", fail_reason=?");
        }
        sql.append(" WHERE xid=? AND status IN (");
        for (int i = 0; i < allowedFrom.length; i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        List<Object> args = new java.util.ArrayList<>();
        args.add(to.name());
        args.add(System.currentTimeMillis());
        if (reason != null) {
            args.add(truncate(reason));
        }
        args.add(xid);
        for (GlobalStatus s : allowedFrom) {
            args.add(s.name());
        }
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    /** 迁入 CANCELLING（互斥 CAS）：已 CONFIRMING/CONFIRMED 的事务绝不可被回滚覆盖。 */
    private boolean toCancelling(String xid, String reason) {
        return casGlobalIn(xid, GlobalStatus.CANCELLING, reason,
                GlobalStatus.TRYING, GlobalStatus.WAITING, GlobalStatus.CANCELLING);
    }

    /** 推迟 WAITING 的下次扫描时间：不累加 retry_count，避免等待外部终态被误判补偿耗尽。 */
    private void postponeWaiting(String xid) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE dtx_transaction SET next_retry_at=?, updated_at=? WHERE xid=? AND status=?",
                now + WAITING_POLL_MS, now, xid, GlobalStatus.WAITING.name());
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