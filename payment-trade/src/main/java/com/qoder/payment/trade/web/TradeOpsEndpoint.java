package com.qoder.payment.trade.web;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.trade.TradeProductDTO;
import com.qoder.payment.trade.dataobject.EscrowConfirmDO;
import com.qoder.payment.trade.dataobject.OrderExpireDO;
import com.qoder.payment.trade.product.ProductRouter;
import com.qoder.payment.trade.service.EscrowHoldAccountService;
import com.qoder.payment.trade.service.EscrowService;
import com.qoder.payment.trade.service.OrderExpireService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-3/T-1/T-2/T-4 交易产品运维端点 {@code /actuator/pptrade}（内嵌 management 端口 18084，
 * 与业务 HTTP/Dubbo 错开；不经网关暴露，属内网运维面，与 ppshard/ppjob 同例）。
 *
 * <p>只读：产品目录与生效参数、配置 provider、担保户登记、确认/关闭留痕计数。
 * <p>写入：{@code refresh}（DynamicConfig 立即重读，供配置热更断言）、
 * {@code expire}/{@code escrowConfirm}（与 JobHandler 同一实现的业务入口，带分片参数）。
 * 作业调度互斥仍由 {@code /actuator/ppjob} 的 JobHandler + ShedLock 路径保证。
 */
@Endpoint(id = "pptrade")
public class TradeOpsEndpoint {

    private final ProductRouter productRouter;
    private final DynamicConfig dynamicConfig;
    private final EscrowService escrowService;
    private final OrderExpireService expireService;
    private final EscrowHoldAccountService holdAccounts;

    public TradeOpsEndpoint(ProductRouter productRouter, DynamicConfig dynamicConfig,
                            EscrowService escrowService, OrderExpireService expireService,
                            EscrowHoldAccountService holdAccounts) {
        this.productRouter = productRouter;
        this.dynamicConfig = dynamicConfig;
        this.escrowService = escrowService;
        this.expireService = expireService;
        this.holdAccounts = holdAccounts;
    }

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", dynamicConfig.provider());
        List<TradeProductDTO> products = productRouter.catalog();
        out.put("products", products);
        EscrowHoldAccountService.Hold hold = holdAccounts.find();
        out.put("holdMerchantId", hold == null ? null : hold.memberId());
        out.put("holdAccountId", hold == null ? null : hold.accountId());
        // P2-H 修正：总量真值（原 recent(500).size() 在留痕超 500 行后饱和，增量断言恒 0）
        out.put("escrowConfirms", escrowService.countConfirms());
        out.put("escrowAutoConfirms", escrowService.countAutoConfirms());
        out.put("expireCloses", expireService.count());
        out.put("expireRaceReopens", expireService.countReopened());
        Map<String, String> config = new LinkedHashMap<>();
        dynamicConfig.snapshot().forEach((k, v) -> {
            if (k.startsWith("trade.")) {
                config.put(k, v);
            }
        });
        out.put("tradeConfig", config);
        return out;
    }

    @WriteOperation
    public Map<String, Object> trigger(@Nullable String action, @Nullable Integer shardIndex,
                                       @Nullable Integer shardTotal) {
        String act = action == null || action.isBlank() ? "refresh" : action.trim();
        int index = shardIndex == null ? 0 : shardIndex;
        int total = shardTotal == null ? 1 : shardTotal;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", act);
        switch (act) {
            case "refresh" -> out.put("refreshed", dynamicConfig.refresh());
            case "expire" -> out.put("closed", expireService.closeExpired(index, total));
            case "escrowConfirm" -> out.put("confirmed", escrowService.autoConfirmDue(index, total));
            case "preview" -> out.put("due", expireService.due(index, total, 200).size());
            default -> out.put("error", "未知 action（refresh/expire/escrowConfirm/preview）");
        }
        out.put("shardIndex", index);
        out.put("shardTotal", total);
        return out;
    }
}