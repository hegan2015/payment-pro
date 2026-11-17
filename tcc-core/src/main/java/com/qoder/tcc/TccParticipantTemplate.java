package com.qoder.tcc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * TCC 参与者模板（XTS 风格）。
 *
 * <p>通过分支日志表 tcc_branch_log 在参与者本地事务内解决三大经典问题：
 * <ul>
 *   <li>幂等：主键 (xid, branch_id, action) 冲突即返回既有结果；</li>
 *   <li>空回滚：Cancel 先于 Try 到达时落 CANCELLED 标记，不执行任何业务；</li>
 *   <li>防悬挂：Try 晚于 Cancel 到达时被 CANCELLED 标记拒绝。</li>
 * </ul>
 * 日志写入与业务动作在同一 REQUIRES_NEW 事务中提交，保证原子性。
 */
@Slf4j
public class TccParticipantTemplate {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TccParticipantTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void tryAction(TccBranchRequest req, Runnable business) {
        tx.executeWithoutResult(s -> {
            try {
                insertLog(req, ParticipantStatus.TRYING);
            } catch (DuplicateKeyException e) {
                ParticipantStatus existing = currentStatus(req);
                if (existing == ParticipantStatus.CANCELLED) {
                    throw new TccException(TccException.CONFLICT, "TCC 悬挂拒绝: " + req.getBranchId());
                }
                log.info("TCC Try 幂等命中 xid={} branch={} status={}", req.getXid(), req.getBranchId(), existing);
                return;
            }
            try {
                business.run();
            } catch (RuntimeException e) {
                // Try 业务失败：未预留任何资源，删除日志，后续 Cancel 走空回滚
                jdbc.update("DELETE FROM tcc_branch_log WHERE xid=? AND branch_id=? AND action=?",
                        req.getXid(), req.getBranchId(), req.getAction());
                throw e;
            }
        });
    }

    public void confirm(TccBranchRequest req, Runnable business) {
        tx.executeWithoutResult(s -> {
            ParticipantStatus status = currentStatus(req);
            if (status == null) {
                throw new TccException(TccException.BIZ_ERROR, "TCC Confirm 无 Try 记录: " + req.getBranchId());
            }
            if (status == ParticipantStatus.CONFIRMED) {
                return;
            }
            if (status == ParticipantStatus.CANCELLED) {
                throw new TccException(TccException.BIZ_ERROR, "TCC 已回滚分支不可提交: " + req.getBranchId());
            }
            business.run();
            updateStatus(req, ParticipantStatus.CONFIRMED);
        });
    }

    public void cancel(TccBranchRequest req, Runnable business) {
        tx.executeWithoutResult(s -> {
            ParticipantStatus status = currentStatus(req);
            if (status == null) {
                insertLog(req, ParticipantStatus.CANCELLED);
                log.info("TCC 空回滚 xid={} branch={}", req.getXid(), req.getBranchId());
                return;
            }
            if (status == ParticipantStatus.CANCELLED) {
                return;
            }
            if (status == ParticipantStatus.CONFIRMED) {
                throw new TccException(TccException.BIZ_ERROR, "TCC 已提交分支不可回滚: " + req.getBranchId());
            }
            business.run();
            updateStatus(req, ParticipantStatus.CANCELLED);
        });
    }

    private void insertLog(TccBranchRequest req, ParticipantStatus status) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO tcc_branch_log(xid,branch_id,action,status,context,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                req.getXid(), req.getBranchId(), req.getAction(), status.name(), req.getContext(), now, now);
    }

    private ParticipantStatus currentStatus(TccBranchRequest req) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM tcc_branch_log WHERE xid=? AND branch_id=? AND action=?",
                req.getXid(), req.getBranchId(), req.getAction());
        return rows.isEmpty() ? null : ParticipantStatus.valueOf((String) rows.get(0).get("status"));
    }

    private void updateStatus(TccBranchRequest req, ParticipantStatus status) {
        jdbc.update("UPDATE tcc_branch_log SET status=?, updated_at=? WHERE xid=? AND branch_id=? AND action=?",
                status.name(), System.currentTimeMillis(), req.getXid(), req.getBranchId(), req.getAction());
    }
}