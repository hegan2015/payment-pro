package com.qoder.payment.trade.product;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.trade.OrderCreateRequest;
import com.qoder.payment.facade.trade.TradeProductDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-3 多交易产品路由：按订单属性把订单路由到对应产品状态机，产品与参数经 I-2 DynamicConfig 可配。
 *
 * <p>路由优先级（先具体后宽泛）：
 * <ol>
 *   <li>显式 {@code productType}（未知产品码拒绝，绝不静默落错状态机）；</li>
 *   <li>订单 {@code bizType} 属性（ESCROW/PREAUTH）；</li>
 *   <li>商户级覆盖 {@code trade.product.merchant.<merchantId>}（可选）；</li>
 *   <li>全局缺省 {@code trade.product.default}（默认 INSTANT）。</li>
 * </ol>
 *
 * <p><b>默认行为红线</b>：不传 productType、bizType 为 TRADE/TOPUP/WITHDRAW/空 的订单一律 INSTANT，
 * 且 INSTANT 恒启用（不可被配置关闭）——ride {@code trade.create} 路径零 RPC 增量、逐字节不变。
 * 产品开关只影响新产品：关闭时显式请求该产品被拒（fail-closed），不影响存量默认产品。
 */
@Slf4j
@Component
public class ProductRouter {

    private final Map<TradeProduct, ProductStateMachine> machines = new LinkedHashMap<>();
    private final DynamicConfig dynamicConfig;

    public ProductRouter(List<ProductStateMachine> stateMachines, DynamicConfig dynamicConfig) {
        this.dynamicConfig = dynamicConfig;
        for (ProductStateMachine machine : stateMachines) {
            ProductStateMachine previous = machines.put(machine.product(), machine);
            if (previous != null) {
                throw new IllegalStateException("产品状态机重复: " + machine.product());
            }
        }
        log.info("T-3 交易产品路由就绪 products={}", machines.keySet());
    }

    /** 下单路由（含启用开关校验）。 */
    public TradeProduct route(OrderCreateRequest request, MemberDTO seller) {
        TradeProduct resolved;
        String explicit = request.getProductType();
        if (explicit != null && !explicit.isBlank()) {
            resolved = parse(explicit);
        } else if (isProductCode(request.getBizType())) {
            resolved = parse(request.getBizType());
        } else {
            String override = dynamicConfig.get(TradeProductKeys.MERCHANT_ROUTE_PREFIX + seller.getId(), null);
            resolved = override != null && !override.isBlank()
                    ? parse(override)
                    : TradeProduct.of(dynamicConfig.get(TradeProductKeys.DEFAULT_PRODUCT, TradeProduct.INSTANT.code()));
        }
        if (!enabled(resolved)) {
            throw new BizException(ResultCode.BIZ_ERROR, "交易产品未启用（DynamicConfig "
                    + TradeProductKeys.enabledKey(resolved) + "=false）: " + resolved);
        }
        return resolved;
    }

    /** 按订单已落库的产品码取状态机（存量 NULL → INSTANT）。 */
    public ProductStateMachine of(String productType) {
        return machine(TradeProduct.of(productType));
    }

    public ProductStateMachine machine(TradeProduct product) {
        ProductStateMachine machine = machines.get(product);
        if (machine == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "产品状态机未注册: " + product);
        }
        return machine;
    }

    /** INSTANT 恒启用（默认产品不可被配置关闭，保护既有调用面）。 */
    public boolean enabled(TradeProduct product) {
        return product == TradeProduct.INSTANT
                || dynamicConfig.getBool(TradeProductKeys.enabledKey(product), true);
    }

    /** 产品目录 + 生效参数快照（只读，供控制台与 verify-trade.py 断言）。 */
    public List<TradeProductDTO> catalog() {
        String defaultCode = TradeProduct.of(dynamicConfig.get(TradeProductKeys.DEFAULT_PRODUCT,
                TradeProduct.INSTANT.code())).code();
        List<TradeProductDTO> out = new ArrayList<>();
        for (TradeProduct product : TradeProduct.values()) {
            ProductStateMachine machine = machines.get(product);
            TradeProductDTO dto = new TradeProductDTO();
            dto.setProduct(product.code());
            dto.setEnabled(enabled(product));
            dto.setDefaultProduct(product.code().equals(defaultCode));
            dto.setStateMachine(machine == null ? null : machine.getClass().getSimpleName());
            dto.setStates(machine == null ? List.of() : machine.states());
            dto.setDescription(machine == null ? null : machine.description());
            dto.setParams(params(product));
            out.add(dto);
        }
        return out;
    }

    private Map<String, String> params(TradeProduct product) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(TradeProductKeys.enabledKey(product), String.valueOf(enabled(product)));
        params.put(TradeProductKeys.DEFAULT_PRODUCT,
                dynamicConfig.get(TradeProductKeys.DEFAULT_PRODUCT, TradeProduct.INSTANT.code()));
        params.put(TradeProductKeys.ORDER_EXPIRE_MINUTES, String.valueOf(dynamicConfig.getInt(
                TradeProductKeys.ORDER_EXPIRE_MINUTES, TradeProductKeys.DEFAULT_ORDER_EXPIRE_MINUTES)));
        if (product == TradeProduct.ESCROW) {
            params.put(TradeProductKeys.ESCROW_AUTO_CONFIRM_DAYS, String.valueOf(dynamicConfig.getInt(
                    TradeProductKeys.ESCROW_AUTO_CONFIRM_DAYS, TradeProductKeys.DEFAULT_ESCROW_AUTO_CONFIRM_DAYS)));
            params.put(TradeProductKeys.ESCROW_AUTO_ENABLED, String.valueOf(dynamicConfig.getBool(
                    TradeProductKeys.ESCROW_AUTO_ENABLED, false)));
            params.put(TradeProductKeys.ESCROW_HOLD_MERCHANT, dynamicConfig.get(
                    TradeProductKeys.ESCROW_HOLD_MERCHANT, TradeProductKeys.DEFAULT_ESCROW_HOLD_MERCHANT));
        }
        if (product == TradeProduct.INSTANT) {
            params.put(TradeProductKeys.EXPIRE_AUTO_ENABLED, String.valueOf(dynamicConfig.getBool(
                    TradeProductKeys.EXPIRE_AUTO_ENABLED, false)));
            params.put(TradeProductKeys.EXPIRE_REJECT_PAY, String.valueOf(dynamicConfig.getBool(
                    TradeProductKeys.EXPIRE_REJECT_PAY, false)));
        }
        if (product == TradeProduct.PREAUTH) {
            params.put(TradeProductKeys.PREAUTH_MAX_DAYS, String.valueOf(dynamicConfig.getInt(
                    TradeProductKeys.PREAUTH_MAX_DAYS, TradeProductKeys.DEFAULT_PREAUTH_MAX_DAYS)));
        }
        return params;
    }

    private static boolean isProductCode(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return false;
        }
        String code = bizType.trim().toUpperCase();
        return TradeProduct.ESCROW.code().equals(code) || TradeProduct.PREAUTH.code().equals(code);
    }

    private static TradeProduct parse(String value) {
        try {
            return TradeProduct.parse(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "未知交易产品: " + value + "（支持 INSTANT/ESCROW/PREAUTH）");
        }
    }
}