package com.qoder.payment.dtx;

import com.qoder.payment.facade.tcc.TccBranchRequest;
import com.qoder.tcc.TccException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 参与者模板薄适配：继承 tcc-core 通用模板，仅做两件事——
 * ① 提供接收 Dubbo 契约 DTO 的重载（三个参与域 Facade 实现零改动）；
 * ② 把组件抛出的 TccException 转回 BizException，保证 FacadeSupport
 * 转出的 Result 错误码/消息与组件化前完全一致。
 * 幂等/空回滚/防悬挂语义全部在 tcc-core 内，零改动。
 */
public class TccParticipantTemplate extends com.qoder.tcc.TccParticipantTemplate {

    public TccParticipantTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        super(jdbc, txManager);
    }

    /** Dubbo 契约 DTO 重载：转换后委托组件模板。 */
    public void tryAction(TccBranchRequest req, Runnable business) {
        tryAction(TccRequests.toCore(req), business);
    }

    public void confirm(TccBranchRequest req, Runnable business) {
        confirm(TccRequests.toCore(req), business);
    }

    public void cancel(TccBranchRequest req, Runnable business) {
        cancel(TccRequests.toCore(req), business);
    }

    @Override
    public void tryAction(com.qoder.tcc.TccBranchRequest req, Runnable business) {
        try {
            super.tryAction(req, business);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }

    @Override
    public void confirm(com.qoder.tcc.TccBranchRequest req, Runnable business) {
        try {
            super.confirm(req, business);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }

    @Override
    public void cancel(com.qoder.tcc.TccBranchRequest req, Runnable business) {
        try {
            super.cancel(req, business);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }
}