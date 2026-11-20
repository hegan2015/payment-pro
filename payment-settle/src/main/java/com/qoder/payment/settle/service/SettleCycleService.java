package com.qoder.payment.settle.service;

import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.settle.SettleCycleDTO;
import com.qoder.payment.settle.mapper.SettleCycleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ST-1 结算周期服务：商户 T+N 配置，N 经 DynamicConfig {@code settle.cycle.default-days}
 * 热更（默认 1 = T+1）。配置变更只影响<b>后续跑批的到期判定</b>，不改任何已生成批次与结算单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleCycleService {

    public static final String KEY_DEFAULT_DAYS = "settle.cycle.default-days";
    public static final int DEFAULT_CYCLE_DAYS = 1;

    private final SettleCycleMapper settleCycleMapper;
    private final DynamicConfig dynamicConfig;

    public int defaultCycleDays() {
        return Math.max(0, dynamicConfig.getInt(KEY_DEFAULT_DAYS, DEFAULT_CYCLE_DAYS));
    }

    /** 无配置返回动态默认（不落库，避免跑批凭空写入商户配置）。 */
    public SettleCycleDTO get(String merchantId) {
        SettleCycleDTO existing = settleCycleMapper.select(merchantId);
        if (existing != null) {
            return existing;
        }
        SettleCycleDTO dto = new SettleCycleDTO();
        dto.setMerchantId(merchantId);
        dto.setCycleDays(defaultCycleDays());
        dto.setStatus("DEFAULT");
        return dto;
    }

    public int cycleDaysOf(String merchantId) {
        SettleCycleDTO existing = settleCycleMapper.select(merchantId);
        return existing == null || existing.getCycleDays() == null
                ? defaultCycleDays() : Math.max(0, existing.getCycleDays());
    }

    public SettleCycleDTO save(SettleCycleDTO cycle) {
        if (cycle == null || cycle.getMerchantId() == null || cycle.getMerchantId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "merchantId 必填");
        }
        if (cycle.getCycleDays() == null || cycle.getCycleDays() < 0 || cycle.getCycleDays() > 30) {
            throw new BizException(ResultCode.PARAM_ERROR, "cycleDays 需在 0~30（T+N 自然日）");
        }
        if (cycle.getFeeBps() != null && (cycle.getFeeBps() < 0 || cycle.getFeeBps() > 10000)) {
            throw new BizException(ResultCode.PARAM_ERROR, "feeBps 需在 0~10000");
        }
        long now = System.currentTimeMillis();
        cycle.setStatus(cycle.getStatus() == null || cycle.getStatus().isBlank() ? "ACTIVE" : cycle.getStatus());
        cycle.setUpdatedAt(now);
        SettleCycleDTO existing = settleCycleMapper.select(cycle.getMerchantId());
        if (existing == null) {
            cycle.setCreatedAt(now);
            settleCycleMapper.insert(cycle);
        } else {
            cycle.setCreatedAt(existing.getCreatedAt());
            settleCycleMapper.update(cycle);
        }
        log.info("ST-1 结算周期保存 merchant={} cycleDays={} feeBps={}",
                cycle.getMerchantId(), cycle.getCycleDays(), cycle.getFeeBps());
        return cycle;
    }
}