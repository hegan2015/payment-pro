package com.qoder.payment.channel.service;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.dispute.DisputeCreateRequest;
import com.qoder.payment.facade.dispute.DisputeDTO;
import com.qoder.payment.facade.dispute.DisputeFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/**
 * CH-4/CH-2b 差错接入（只调 recon 域 {@link DisputeFacade}，<b>不改 recon 域任何代码</b>）。
 *
 * <p>source=PAYMENT（跨泳道登记口径），checkName 区分核对场景（CHANNEL_BILL / CHANNEL_ASYNC），
 * bizDate 参与幂等维度；重复登记由差错平台 (DISPUTE, source:checkName:bizDate:bizNo) 去重吸收。
 * 差错平台不可达 → 记 ERROR + 返回 null（不阻断渠道主流程，下载/补单可重试）。
 */
@Slf4j
@Component
public class ChannelDisputeReporter {

    public static final String SOURCE = "PAYMENT";
    public static final String CHECK_BILL = "CHANNEL_BILL";
    public static final String CHECK_ASYNC = "CHANNEL_ASYNC";

    @DubboReference(check = false, timeout = 5000, retries = 0)
    private DisputeFacade disputeFacade;

    /** 登记差错单，返回 disputeId（不可达/失败返回 null）。 */
    public Long report(String checkName, String bizDate, String type, String bizNo, String paymentId,
                       Long amount, String strategy, String reason) {
        DisputeCreateRequest request = new DisputeCreateRequest();
        request.setSource(SOURCE);
        request.setCheckName(checkName);
        request.setBizDate(bizDate);
        request.setType(type);
        request.setBizNo(bizNo);
        request.setPaymentId(paymentId);
        request.setAmount(amount);
        request.setStrategy(strategy);
        request.setReason(reason);
        try {
            Result<DisputeDTO> result = disputeFacade.create(request);
            if (result == null || !result.isSuccess() || result.getData() == null) {
                log.error("[告警] 渠道差错登记未成功 bizNo={} code={} message={}", bizNo,
                        result == null ? null : result.getCode(), result == null ? null : result.getMessage());
                return null;
            }
            log.warn("[告警] 渠道差错登记 disputeId={} type={} bizNo={} reason={}",
                    result.getData().getId(), type, bizNo, reason);
            return result.getData().getId();
        } catch (Exception e) {
            log.error("[告警] 差错平台不可达，渠道差错登记失败 bizNo={}: {}", bizNo, e.getMessage());
            return null;
        }
    }
}