package com.qoder.payment.channel.spi;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CH-2b/CH-4 故障注入器（沙箱与账单混沌靶，进程内内存态，重启即恢复出厂）。
 *
 * <p><b>沙箱即沙箱</b>：本注入器只作用于本进程内的模拟银行/沙箱适配器与其生成的账单文件，
 * 不连接任何真实银行/支付机构，不改写任何既有业务数据（channel_trade / 账务 / 支付单一律不动）。
 *
 * <p>沙箱四类故障：延迟（delayMs）、拒绝（reject）、回执丢失（receiptLoss）、金额不符（amountMismatch），
 * 另有宕机（down，HTTP 503 → 确未受理 → CH-3 故障切换）与超时（timeoutMs）。
 * 账单三类差异：金额不符（amountDelta）、渠道多单（extraRows）、平台多单（dropBizNo → 账单缺行）。
 */
@Data
@Component
public class ChannelFaultInjector {

    /** 沙箱：回执延迟毫秒（null=用配置默认）。 */
    private volatile Long delayMs;
    /** 沙箱：异步拒绝（终态 FAILED）。 */
    private volatile boolean reject;
    /** 沙箱：回执丢失（只落终态不推回执，平台必须查询补单）。 */
    private volatile boolean receiptLoss;
    /** 沙箱：金额不符（渠道侧金额 = 请求金额 + amountDelta）。 */
    private volatile boolean amountMismatch;
    /** 沙箱：金额差额（分，默认 +100）。 */
    private volatile long amountDelta = 100L;
    /** 沙箱：宕机（submit/query/bill 一律 503）。 */
    private volatile boolean down;
    /** 沙箱：受理响应超时毫秒（0=不注入；超时后状态未知 → PROCESSING，不臆断失败）。 */
    private volatile long timeoutMs;
    /** 故障作用范围：指定 bizNo（null=全部沙箱单）。 */
    private volatile String targetBizNo;

    /** 账单故障：channelCode → 注入项。 */
    private final Map<String, BillFault> billFaults = new ConcurrentHashMap<>();

    @Data
    public static class BillFault {
        /** 金额不符的目标 bizNo 与差额（分）。 */
        private String amountBizNo;
        private long amountDelta;
        /** 渠道多单（平台无此流水）：原始 CSV 明细行。 */
        private List<String> extraRows = new ArrayList<>();
        /** 账单缺行（平台有流水账单无）：目标 bizNo。 */
        private String dropBizNo;
        /** 声明汇总差额（分）：只改 #TOTAL 不改明细 → 触发解析完整性校验失败。 */
        private long totalDelta;
    }

    public BillFault billFault(String channelCode) {
        return billFaults.get(channelCode);
    }

    public BillFault billFaultOrCreate(String channelCode) {
        return billFaults.computeIfAbsent(channelCode, k -> new BillFault());
    }

    public boolean applies(String bizNo) {
        String target = targetBizNo;
        return target == null || target.isBlank() || target.equals(bizNo);
    }

    /** 全部复位（验收隔离 / 混沌结束恢复）。 */
    public void reset() {
        delayMs = null;
        reject = false;
        receiptLoss = false;
        amountMismatch = false;
        amountDelta = 100L;
        down = false;
        timeoutMs = 0L;
        targetBizNo = null;
        billFaults.clear();
    }

    public void resetBill(String channelCode) {
        billFaults.remove(channelCode);
    }
}