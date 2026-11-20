package com.qoder.payment.payment.service;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelTradeDTO;
import com.qoder.tcc.TccCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

/**
 * 渠道终态 → TCC 二阶段驱动（CH-1/S-5）：回执/查询得到的渠道终态经此收敛支付单。
 *
 * <p>查询失败一律返回 null（状态未知），由决策表判 WAIT，绝不臆断成功/失败；
 * resume 经协调器状态 CAS（WAITING→CONFIRMING/CANCELLING 只一次）+ 参与者分支幂等，
 * 与回执并发/重复补单均无重复过账、无重复 outbox。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelCompletionService {

    private final TccCoordinator coordinator;

    @DubboReference
    private ChannelFacade channelFacade;

    /** 查渠道当前 CAS 状态；不可达/无记录返回 null（未知）。 */
    public String queryStatus(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return null;
        }
        try {
            Result<ChannelTradeDTO> result = channelFacade.queryChannelTrade(bizNo);
            if (result == null || !result.isSuccess()) {
                log.warn("渠道查询未成功 bizNo={} code={} message={}", bizNo,
                        result == null ? null : result.getCode(), result == null ? null : result.getMessage());
                return null;
            }
            ChannelTradeDTO trade = result.getData();
            return trade == null ? null : trade.getStatus();
        } catch (Exception e) {
            log.warn("渠道查询异常 bizNo={}: {}", bizNo, e.getMessage());
            return null;
        }
    }

    /** 渠道已扣款 → 提交二阶段（过账+订单 PAID+支付 SUCCESS+outbox，均由完结监听原子落库）。 */
    public boolean resumeCommit(String xid) {
        return coordinator.resumeCommit(xid);
    }

    /** 渠道失败/撤销 → 回滚二阶段（解冻+释放+支付 FAILED）。 */
    public boolean resumeCancel(String xid, String reason) {
        return coordinator.resumeCancel(xid, reason);
    }
}