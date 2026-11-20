package com.qoder.payment.trade.config;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.trade.product.ProductRouter;
import com.qoder.payment.trade.service.EscrowHoldAccountService;
import com.qoder.payment.trade.service.EscrowService;
import com.qoder.payment.trade.service.OrderExpireService;
import com.qoder.payment.trade.web.TradeOpsEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-3/T-1/T-2/T-4 运维端点装配（{@code /actuator/pptrade}，需域配置 exposure.include 列出 pptrade）。
 * 与既有 ppshard 端点并存，互不影响；local/docker 同构（无新中间件）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Endpoint.class)
public class TradeEndpointConfig {

    @Bean
    public TradeOpsEndpoint tradeOpsEndpoint(ProductRouter productRouter, DynamicConfig dynamicConfig,
                                             EscrowService escrowService, OrderExpireService expireService,
                                             EscrowHoldAccountService holdAccounts) {
        return new TradeOpsEndpoint(productRouter, dynamicConfig, escrowService, expireService, holdAccounts);
    }
}