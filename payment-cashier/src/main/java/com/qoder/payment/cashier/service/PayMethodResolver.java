package com.qoder.payment.cashier.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.BankCardDTO;
import com.qoder.payment.facade.cashier.PayMethodDTO;
import com.qoder.payment.facade.trade.OrderDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * C-1 多支付方式可用性判定（收银台只渲染聚合，不做业务决策）。
 *
 * <p>REAL（当前可受理）：BALANCE（账户 ACTIVE+余额足额）、CARD（沙箱银行渠道）、
 * PAY_CODE/SCAN（C-2 沙箱被扫/主扫）。RESERVED（预留扩展位，标 available=false+原因）：
 * QUICK_PAY（绑卡 A-2）、NET_BANK（渠道 CH-5/维护窗口）、CREDIT（授信 A-1）、
 * COUPON（券 X-3/P-7）、COMBO（组合 P-2）。可用性/限额/维护窗口/启用开关经 I-2 DynamicConfig。
 *
 * <p>金额一律「分」，limit 字段为分，展示层由前端转元。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayMethodResolver {

    private final DynamicConfig config;

    @DubboReference
    private AccountFacade accountFacade;

    /**
     * @param order            交易单
     * @param candidateAccounts 候选余额账户（聚合=全部 ACTIVE USER；指定付款人=该账户）
     * @param payerAccountId   指定付款账户（可空=聚合渲染）
     */
    public List<PayMethodDTO> resolve(OrderDTO order, List<AccountDTO> candidateAccounts, String payerAccountId) {
        long amount = order.getAmount() == null ? 0L : order.getAmount();
        List<PayMethodDTO> out = new ArrayList<>();
        out.add(balance(order, candidateAccounts, payerAccountId, amount));
        out.add(card(amount));
        out.add(payCode(amount));
        out.add(scan(amount));
        out.add(quickPay(payerAccountId, amount));
        out.add(netBank(amount));
        out.add(credit(amount));
        out.add(coupon(amount));
        out.add(combo(amount));
        return out;
    }

    private PayMethodDTO base(String method, String name, String hint, String icon, String category, long limit) {
        PayMethodDTO m = new PayMethodDTO();
        m.setMethod(method);
        m.setName(name);
        m.setHint(hint);
        m.setIcon(icon);
        m.setCategory(category);
        m.setLimit(limit > 0 ? limit : null);
        m.setMaintenance(false);
        m.setEnabled(config.getBool("cashier.method." + method.toLowerCase() + ".enabled", true));
        m.setAccounts(List.of());
        return m;
    }

    private PayMethodDTO balance(OrderDTO order, List<AccountDTO> accounts, String payerAccountId, long amount) {
        long limit = config.getLong("cashier.balance.limit", 5_000_000L);
        PayMethodDTO m = base("BALANCE", "余额", "使用账户可用余额付款", "wallet", "REAL", limit);
        List<AccountDTO> active = accounts == null ? List.of()
                : accounts.stream().filter(a -> "ACTIVE".equals(a.getStatus())).toList();
        m.setAccounts(active);
        if (!m.getEnabled()) {
            return unavailable(m, "余额支付已停用（配置）");
        }
        if (active.isEmpty()) {
            return unavailable(m, payerAccountId != null ? "账户不可用（冻结或不存在）" : "无可用余额账户");
        }
        boolean sufficient = active.stream().anyMatch(a -> a.getBalance() != null && a.getBalance() >= amount);
        if (!sufficient) {
            return unavailable(m, "余额不足");
        }
        if (limit > 0 && amount > limit) {
            return unavailable(m, "超出单笔限额 ¥" + yuan(limit));
        }
        m.setAvailable(true);
        m.setUnavailableReason(null);
        return m;
    }

    private PayMethodDTO card(long amount) {
        long limit = config.getLong("cashier.card.limit", 5_000_000L);
        PayMethodDTO m = base("CARD", "银行卡支付", "沙箱银行渠道，尾号 0000 的卡会被拒绝", "bank", "REAL", limit);
        if (!m.getEnabled()) {
            return unavailable(m, "银行卡支付已停用（配置）");
        }
        if (limit > 0 && amount > limit) {
            return unavailable(m, "超出单笔限额 ¥" + yuan(limit));
        }
        m.setAvailable(true);
        return m;
    }

    private PayMethodDTO payCode(long amount) {
        PayMethodDTO m = base("PAY_CODE", "付款码", "向商家出示付款码完成支付（沙箱·被扫）", "code", "REAL", 0);
        if (!m.getEnabled()) {
            return unavailable(m, "付款码已停用（配置）");
        }
        m.setAvailable(true);
        m.setHint("沙箱付款码：每分钟刷新、一次性核销、防重放；不接真实微信/支付宝");
        return m;
    }

    private PayMethodDTO scan(long amount) {
        PayMethodDTO m = base("SCAN", "扫一扫", "扫描商家收款码付款（沙箱·主扫）", "scan", "REAL", 0);
        if (!m.getEnabled()) {
            return unavailable(m, "扫一扫已停用（配置）");
        }
        m.setAvailable(true);
        m.setHint("沙箱扫码：解析商家收款码 → 验证 → 幂等受理；不接真实微信/支付宝");
        return m;
    }

    private PayMethodDTO quickPay(String payerAccountId, long amount) {
        long limit = config.getLong("cashier.quickpay.limit", 5_000_000L);
        PayMethodDTO m = base("QUICK_PAY", "快捷支付", "已签约银行卡快捷付款", "bank", "RESERVED", limit);
        if (!m.getEnabled()) {
            return unavailable(m, "快捷支付未启用");
        }
        // 绑卡签约（A-2）：指定付款人时实时查其签约卡；聚合渲染或无卡 → 预留不可用
        if (payerAccountId != null) {
            try {
                List<BankCardDTO> cards = accountFacade.listCards(payerAccountId).orThrow();
                boolean signed = cards != null && cards.stream()
                        .anyMatch(c -> c.getSignNo() != null && !c.getSignNo().isBlank()
                                && !"UNBOUND".equals(c.getStatus()));
                if (signed) {
                    m.setAvailable(true);
                    m.setCategory("REAL");
                    return m;
                }
            } catch (Exception e) {
                log.debug("快捷支付签约查询降级（绑卡 A-2 泳道）: {}", e.getMessage());
            }
        }
        return unavailable(m, "未签约快捷支付（绑卡 A-2 泳道）");
    }

    private PayMethodDTO netBank(long amount) {
        PayMethodDTO m = base("NET_BANK", "网银支付", "银行网银渠道", "bank-net", "RESERVED", 0);
        String window = config.get("cashier.netbank.maintenance", "02:00-04:00");
        boolean inWindow = inMaintenanceWindow(window);
        m.setMaintenance(inWindow);
        if (inWindow) {
            return unavailable(m, "网银渠道维护中（" + window + "）");
        }
        if (!m.getEnabled()) {
            return unavailable(m, "网银支付未启用");
        }
        return unavailable(m, "网银渠道即将上线（CH-5）");
    }

    private PayMethodDTO credit(long amount) {
        PayMethodDTO m = base("CREDIT", "信用支付", "授信账户先享后付", "huabei", "RESERVED", 0);
        return unavailable(m, "未开通授信账户（A-1 授信）");
    }

    private PayMethodDTO coupon(long amount) {
        PayMethodDTO m = base("COUPON", "优惠券", "营销券抵扣", "redpacket", "RESERVED", 0);
        return unavailable(m, "无可用优惠券（X-3 营销 P2）");
    }

    private PayMethodDTO combo(long amount) {
        PayMethodDTO m = base("COMBO", "组合支付", "余额+银行卡+券组合付款", "combo", "RESERVED", 0);
        return unavailable(m, "组合支付即将上线（P-2 泳道）");
    }

    private PayMethodDTO unavailable(PayMethodDTO m, String reason) {
        m.setAvailable(false);
        m.setUnavailableReason(reason);
        return m;
    }

    /** 维护窗口 "HH:mm-HH:mm"（可跨午夜）；解析失败视为不在窗口。 */
    private boolean inMaintenanceWindow(String window) {
        try {
            if (window == null || !window.contains("-")) {
                return false;
            }
            String[] parts = window.split("-");
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            LocalTime now = LocalTime.now();
            if (start.isAfter(end)) {
                return !now.isBefore(start) || !now.isAfter(end);
            }
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (Exception e) {
            return false;
        }
    }

    private static String yuan(long cents) {
        return String.format("%.2f", cents / 100.0);
    }
}