package com.qoder.payment.facade.tcc;

import com.qoder.payment.common.result.Result;
import com.qoder.tcc.TccBranchRequest;

/**
 * TCC 参与者统一接口。每个参与域以 Dubbo group 区分暴露，
 * 协调器按 group 引用。三个方法均须满足幂等。
 */
public interface TccParticipantFacade {

    /** 一阶段：预留资源。失败则协调器发起全局 Cancel。 */
    Result<Void> tryAction(TccBranchRequest request);

    /** 二阶段提交：使用预留资源完成业务。必须幂等、可重试。 */
    Result<Void> confirm(TccBranchRequest request);

    /** 二阶段回滚：释放预留资源。须处理空回滚与悬挂。 */
    Result<Void> cancel(TccBranchRequest request);
}