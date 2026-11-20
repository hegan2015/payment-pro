package com.qoder.payment.common.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.ResourceTypeConstants;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I-7 手动埋点守卫（热点参数限流等框架扩展位）。
 *
 * <p><b>fail-open 红线</b>：被规则拒绝返回 false（调用方转规范错误码）；
 * Sentinel 自身故障（类缺失/初始化失败等 {@link Exception} 或 {@link LinkageError}）
 * 一律返回 true 放行——治理组件不可用不得阻断支付。
 *
 * <p>QPS 口径：entry 后立即 exit（计数型规则与占用时长无关）。
 */
public final class SentinelGuard {

    private static final Logger log = LoggerFactory.getLogger(SentinelGuard.class);

    private SentinelGuard() {
    }

    /**
   * 尝试通过资源守卫。
   *
   * @param resource  资源名（如 openapi:trade.create）
   * @param hotParams 热点参数（如 appId，对应 ParamFlowRule.paramIdx）
   * @return true=放行（含 Sentinel 故障兜底）；false=被限流/熔断规则拒绝
   */
    public static boolean tryPass(String resource, Object... hotParams) {
        Entry entry = null;
        try {
            // sentinel-core 1.8.7 API：entry(String,int resourceType,EntryType,Object[] args)，batchCount 内置 1
            entry = SphU.entry(resource, ResourceTypeConstants.COMMON, EntryType.IN, hotParams);
            return true;
        } catch (BlockException e) {
            return false;
        } catch (Exception | LinkageError t) {
            log.warn("Sentinel 守卫故障，默认放行 resource={}: {}", resource, t.toString());
            return true;
        } finally {
            if (entry != null) {
                try {
                    entry.exit(1, hotParams);
                } catch (Exception | LinkageError t) {
                    log.warn("Sentinel entry 退出异常（忽略）: {}", t.toString());
                }
            }
        }
    }
}