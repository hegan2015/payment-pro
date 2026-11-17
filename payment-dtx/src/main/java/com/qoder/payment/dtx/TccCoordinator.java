package com.qoder.payment.dtx;

import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.tcc.TccCompletionListener;
import com.qoder.tcc.TccException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * 协调器薄适配：继承 tcc-core 通用协调器，仅做两件事——
 * ① 构造时把 Dubbo 参与者引用桥接为组件 SPI；
 * ② 把组件抛出的 TccException 转回 BizException，保证编排方（FacadeSupport）
 * 看到的错误码/消息与组件化前完全一致。事务语义全部在 tcc-core 内，零改动。
 */
public class TccCoordinator extends com.qoder.tcc.TccCoordinator {

    public TccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          Map<String, TccParticipantFacade> participants, TccCompletionListener listener) {
        super(jdbc, txManager, DubboTccParticipant.wrap(participants), listener);
    }

    @Override
    public String begin(String bizType, String bizNo) {
        try {
            return super.begin(bizType, bizNo);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }

    @Override
    public void tryAll(String xid) {
        try {
            super.tryAll(xid);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }

    @Override
    public void commitAsync(String xid) {
        try {
            super.commitAsync(xid);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }

    @Override
    public void rollbackAsync(String xid, String reason) {
        try {
            super.rollbackAsync(xid, reason);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }
}