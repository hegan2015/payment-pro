package com.qoder.payment.gateway.config;

import com.alibaba.csp.sentinel.adapter.dubbo.config.DubboAdapterGlobalConfig;
import com.alibaba.csp.sentinel.adapter.spring.webmvc.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc.config.SentinelWebMvcConfig;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * I-7 网关治理装配（Sentinel 限流/熔断/降级）。
 *
 * <ul>
 *   <li>Web 层：{@link SentinelWebInterceptor}（6x jakarta adapter）仅挂 <b>/openapi/**</b>，
 *       资源名=URL 模式（如 /openapi/trade.create），URL 级 QPS 总闸；
 *       拒绝经 {@link GatewayBlockExceptionHandler} 返回统一 {error, code}（429 A0429 / 503 B0003），
 *       发生在控制器受理之前——不落 PROCESSING 支付单（资金安全红线）。
 *       控制台 /api/**（已有 Bearer+RBAC+登录限流+StepUp）与 /actuator/**、静态页不挂拦截器，行为不变。</li>
 *   <li>Dubbo 消费端：sentinel-apache-dubbo-adapter 自动激活，覆盖在途 RPC 异常/慢调用；
 *       接口级资源（如 com.qoder.payment.facade.accounting.AccountingFacade）异常数熔断。
 *       Dubbo 3.2 的 "No provider available" 在 Directory 层抛出、绕过 adapter 过滤链，
 *       故 OpenApiController#rpcGuard 在调用边界显式埋点兜底（Tracer 计异常、熔断开 → B0003）。
 *       自定义 consumer fallback 把 BlockException 转规范 BizException（B0003/A0429），
 *       经 GlobalExceptionHandler 映射 503/429，不泄漏堆栈。拒绝发生在 RPC 发起前，
 *       不会启动下游 TCC，故无在途悬挂单。</li>
 *   <li>appId 热点参数限流与可选 nonce 防重放在 OpenApiController#verify（验签通过后、受理前）。</li>
 * </ul>
 *
 * 规则由 payment-common SentinelRuleLoader 加载（local 文件 / docker Apollo 回落文件）；
 * 规则缺失/损坏默认放行，Sentinel 故障 fail-open，均不阻断支付。
 */
@Slf4j
@Configuration
public class SentinelConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SentinelWebMvcConfig config = new SentinelWebMvcConfig();
        config.setBlockExceptionHandler(new GatewayBlockExceptionHandler());
        // httpMethodSpecify=false → 资源名即 /openapi/xxx；webContextUnify 默认 true
        registry.addInterceptor(new SentinelWebInterceptor(config)).addPathPatterns("/openapi/**");
        log.info("Sentinel Web 拦截器已注册 /openapi/**（URL 级限流，受理前拒绝）");
    }

    /** Dubbo 消费端熔断/限流降级：BlockException → 规范错误体（不泄漏堆栈、不启动下游事务）。 */
    @PostConstruct
    public void registerDubboConsumerFallback() {
        try {
            DubboAdapterGlobalConfig.setConsumerFallback((invoker, invocation, ex) -> {
                String resource = ex.getRule() == null ? invoker.getInterface().getName() : ex.getRule().getResource();
                MetricsSupport.count("sentinel_block", "type", ex.getClass().getSimpleName(), "resource", resource);
                BizException biz = toBizException(ex);
                log.warn("Dubbo 消费端 Sentinel 拒绝 resource={} type={} → {}", resource, ex.getClass().getSimpleName(), biz.getCode());
                return AsyncRpcResult.newDefaultAsyncResult(biz, invocation);
            });
            log.info("Sentinel Dubbo 消费端降级 fallback 已注册（B0003/A0429 规范错误体）");
        } catch (Exception | LinkageError t) {
            // adapter 缺失等极端情况：保持默认 fallback（RpcException → C0001），不阻断启动
            log.warn("Sentinel Dubbo fallback 注册失败（保持默认降级）: {}", t.toString());
        }
    }

    /** BlockException → 规范 BizException（Web 拦截器 / Dubbo fallback / 手动守卫共用口径）。 */
    public static BizException toBizException(BlockException ex) {
        if (ex instanceof FlowException || ex instanceof ParamFlowException) {
            return new BizException(ResultCode.TOO_MANY_REQUESTS.getCode(), "请求过于频繁，请稍后重试");
        }
        if (ex instanceof DegradeException) {
            return new BizException(ResultCode.DEGRADED.getCode(), "下游服务熔断降级，请稍后重试");
        }
        return new BizException(ResultCode.DEGRADED.getCode(), "服务暂不可用，请稍后重试");
    }
}