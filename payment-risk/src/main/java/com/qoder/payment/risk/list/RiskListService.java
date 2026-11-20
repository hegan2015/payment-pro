package com.qoder.payment.risk.list;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.facade.risk.ListEntryDTO;
import com.qoder.payment.facade.risk.ListHitDTO;
import com.qoder.payment.facade.risk.RiskAction;
import com.qoder.payment.risk.config.RiskConfigManager;
import com.qoder.payment.risk.config.RiskKeys;
import com.qoder.payment.risk.engine.RiskContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * R-3 名单服务（黑/白/灰 × 用户/商户/卡号/设备/IP）。
 *
 * <p><b>优先级红线（明确并断言）</b>：<b>WHITE &gt; BLACK &gt; GRAY</b>。
 * 同一请求同时命中白名单与黑名单时，白名单胜出 → 放行（跳过黑名单拦截与评分升级），
 * 留痕 {@code listHit=WHITE}；仅命中黑名单 → 按条目 action（默认 REJECT）；
 * 仅命中灰名单 → 默认 CHALLENGE（加强验证）。
 *
 * <p>过期条目（expireAt &le; now）自动失效；软删条目（status=REMOVED）不生效。
 * 名单默认空 → 正常流量零影响；变更审计由 {@code risk_config_snapshot}（LISTS）逐版留痕。
 */
@Slf4j
@Service
public class RiskListService {

    private final RiskConfigManager configs;
    private final DynamicConfig config;

    public RiskListService(RiskConfigManager configs, DynamicConfig config) {
        this.configs = configs;
        this.config = config;
    }

    /** 上下文各维度取值 → (valueType, value) 候选。 */
    private static String[][] candidates(RiskContext ctx) {
        return new String[][]{
                {"USER", ctx.getPayerId()},
                {"MERCHANT", ctx.getMerchantId()},
                {"CARD", ctx.getCardNo()},
                {"CARD", ctx.getCardBin()},
                {"DEVICE", ctx.getDeviceId()},
                {"IP", ctx.getIp()}
        };
    }

    private ListEntryDTO find(String valueType, String value, long now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ListEntryDTO e : configs.lists()) {
            if (!"ENABLED".equals(e.getStatus())) {
                continue;
            }
            if (e.getExpireAt() != null && e.getExpireAt() > 0 && e.getExpireAt() <= now) {
                continue;
            }
            if (valueType.equals(e.getValueType()) && value.equals(e.getValue())) {
                return e;
            }
        }
        return null;
    }

    /** 名单判定：白 > 黑 > 灰；未命中返回 null。 */
    public ListEntryDTO resolve(RiskContext ctx) {
        if (!config.getBool(RiskKeys.LIST_ENABLED, RiskKeys.DEFAULT_LIST_ENABLED)) {
            return null;
        }
        if (configs.lists().isEmpty()) {
            return null;
        }
        ListEntryDTO black = null;
        ListEntryDTO gray = null;
        for (String[] c : candidates(ctx)) {
            ListEntryDTO e = find(c[0], c[1], ctx.getNow());
            if (e == null) {
                continue;
            }
            switch (e.getListType()) {
                case "WHITE" -> {
                    // 白名单优先级最高：立即返回，后续黑名单不再评估
                    return e;
                }
                case "BLACK" -> {
                    if (black == null) {
                        black = e;
                    }
                }
                default -> {
                    if (gray == null) {
                        gray = e;
                    }
                }
            }
        }
        return black != null ? black : gray;
    }

    /** 单点查询（控制台/规则函数用）。 */
    public ListHitDTO hit(String valueType, String value) {
        ListHitDTO dto = new ListHitDTO();
        dto.setHit(false);
        dto.setValueType(valueType);
        dto.setValue(value);
        ListEntryDTO e = find(valueType == null ? "" : valueType.trim().toUpperCase(), value, System.currentTimeMillis());
        if (e != null) {
            dto.setHit(true);
            dto.setListType(e.getListType());
            dto.setAction(e.getAction());
            dto.setReason(e.getReason());
            dto.setEntryId(e.getId());
        }
        return dto;
    }

    /** 规则函数 inList(listType, "VALUE_TYPE:value") 的判定入口。 */
    public boolean inList(String listType, String typeAndValue) {
        if (typeAndValue == null) {
            return false;
        }
        int colon = typeAndValue.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String vt = typeAndValue.substring(0, colon).trim().toUpperCase();
        String v = typeAndValue.substring(colon + 1);
        ListEntryDTO e = find(vt, v, System.currentTimeMillis());
        return e != null && Objects.equals(upper(listType), e.getListType());
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    /** 名单条目 → 处置动作（缺省按类型推导）。 */
    public static String actionOf(ListEntryDTO e) {
        if (e.getAction() != null && !e.getAction().isBlank()) {
            return e.getAction();
        }
        return switch (e.getListType()) {
            case "WHITE" -> RiskAction.PASS;
            case "GRAY" -> RiskAction.CHALLENGE;
            default -> RiskAction.REJECT;
        };
    }

    public static String reasonOf(ListEntryDTO e) {
        String base = e.getReason() == null || e.getReason().isBlank()
                ? "命中" + label(e.getListType()) + "(" + e.getValueType() + ":" + e.getValue() + ")"
                : e.getReason();
        return base;
    }

    private static String label(String listType) {
        return switch (listType) {
            case "WHITE" -> "白名单";
            case "GRAY" -> "灰名单";
            default -> "黑名单";
        };
    }
}