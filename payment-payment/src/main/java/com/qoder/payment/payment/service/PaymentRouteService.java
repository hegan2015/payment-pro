package com.qoder.payment.payment.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelFacade;
import com.qoder.payment.facade.channel.ChannelRouteRequest;
import com.qoder.payment.facade.channel.RouteResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

/**
 * P-6 支付路由（支付方式级编排）：CARD 受理<b>前</b>调 channel 域 CH-3 路由择优
 * （成功率/限额/维护窗口/成本加权评分，预览与受理同一口径）。
 *
 * <p><b>边界（与 A 泳道 CH-3）</b>：渠道级择优/故障切换/限额硬过滤已由 channel 域
 * Router 实现（ChannelService.preauth 内部同一路由器），本域不重复造——只做受理前
 * 预检与选中渠道透传（ChannelTccContext.channelCode）：
 * <ul>
 *   <li>确定性路由拒绝（无可用渠道）→ <b>受理前失败关闭</b>，支付单行都不落，绝不产生悬挂单；</li>
 *   <li>路由不可达（渠道域宕机）→ 降级原路径（渠道 Try 失败 TCC 回滚 FAILED 留痕，资金未动）；</li>
 *   <li>选中渠道随分支上下文透传，渠道侧按指定渠道受理（不可用时明确拒绝，不静默改道）；</li>
 *   <li>BALANCE/资金单无渠道分支，不经过路由（ride 同步红线逐字节不变）。</li>
 * </ul>
 *
 * <p>回滚开关 {@code payment.route.enabled=false}：跳过受理前预检（渠道域内部仍路由），
 * 与升级前行为等价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRouteService {

    private final DynamicConfig dynamicConfig;

    @DubboReference
    private ChannelFacade channelFacade;

    /**
     * CARD 受理前路由择优；返回选中渠道 code。
     *
     * <p>两类失败严格区分：
     * <ul>
     *   <li><b>确定性路由拒绝</b>（路由正常应答但无可用渠道：维护/限额/熔断/停用）→
     *       受理前失败关闭，支付单行都不落，绝不产生悬挂单；</li>
     *   <li><b>路由不可达</b>（渠道域宕机/超时/异常）→ 降级返回 null 走改造前原路径
     *       （渠道分支 Try 失败 → TCC 整体回滚 → FAILED 终态留痕，资金未动不悬挂），
     *       与 verify-pro 渠道宕机混沌口径一致，绝不因预检依赖阻断受理留痕。</li>
     * </ul>
     * 开关 {@code payment.route.enabled=false} 时恒降级（渠道域内部仍路由）。
     */
    public String routeForPay(long amount, String cardNo) {
        if (!dynamicConfig.getBool("payment.route.enabled", true)) {
            return null;
        }
        ChannelRouteRequest request = new ChannelRouteRequest();
        request.setAmount(amount);
        request.setCardNo(cardNo);
        request.setType("PAY");
        Result<RouteResultDTO> result;
        try {
            result = channelFacade.route(request);
        } catch (Exception e) {
            log.warn("渠道路由不可达，降级原路径受理（渠道 Try 失败将回滚留痕）amount={}: {}", amount, e.getMessage());
            MetricsSupport.count("payment_route_degraded");
            return null;
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            log.warn("渠道路由应答异常，降级原路径受理 amount={} code={}", amount,
                    result == null ? null : result.getCode());
            MetricsSupport.count("payment_route_degraded");
            return null;
        }
        RouteResultDTO route = result.getData();
        if (route.getChannelCode() == null || route.getChannelCode().isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道路由拒绝: 无可用渠道 " + route.getReason());
        }
        log.info("P-6 支付路由择优 amount={} channel={} score={} reason={}",
                amount, route.getChannelCode(), route.getScore(), route.getReason());
        return route.getChannelCode();
    }
}