package com.qoder.payment.channel.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.ChannelTradeDO;
import com.qoder.payment.channel.mapper.ChannelTradeMapper;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.channel.ChannelLimitDTO;
import com.qoder.payment.facade.channel.ChannelPayRequest;
import com.qoder.payment.facade.channel.ChannelRefundRequest;
import com.qoder.payment.facade.channel.ChannelResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CH-2b 沙箱银行适配器（SPI 第二个真实实现，异步受理 → 回执/查询终态）。
 *
 * <p><b>沙箱边界（如实说明，不伪造真实银行连接）</b>：对端是本进程内的
 * {@code SandboxBankController}（模拟银行侧账本 sandbox_order），经真实 HTTP + HMAC 签名往返，
 * 但<b>没有任何真实银行/支付机构凭据，也不连接外部网络</b>。channel_config.env=SANDBOX 标识隔离；
 * 接生产渠道时同 SPI 替换 base-url/密钥 ref/报文实现即可（§5.3）。
 *
 * <p>异步语义：pay 受理 → PROCESSING（channel_trade SUBMITTED）→ 沙箱延迟回执 / 平台主动 query
 * → CAPTURED/FAILED → CH-1 resumeCommit/resumeCancel。<b>仅在 {@code payment.channel.async=true}
 * 时才可能被路由选中</b>（同步 stack 硬过滤排除），杜绝「渠道 SUBMITTED 而支付侧直接 commit」的悬挂单。
 *
 * <p>故障注入（{@link ChannelFaultInjector}）：延迟 / 拒绝 / 回执丢失 / 金额不符 / 宕机 / 超时。
 * 超时与网络中断一律 PROCESSING（状态未知，交查询补单），绝不臆断失败。
 */
@Slf4j
@Component
public class SandboxBankAdapter implements ChannelAdapter {

    public static final String CODE = "SANDBOX_BANK";
    public static final String TYPE = "SANDBOX";
    public static final String SECRET_REF = "channel-sandbox_bank";

    private final ChannelHttpClient http;
    private final ChannelTradeMapper tradeMapper;
    private final ChannelFaultInjector faults;
    private final DynamicConfig config;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${payment.channel.async:false}")
    private boolean async;

    public SandboxBankAdapter(ChannelHttpClient http, ChannelTradeMapper tradeMapper,
                              ChannelFaultInjector faults, DynamicConfig config) {
        this.http = http;
        this.tradeMapper = tradeMapper;
        this.faults = faults;
        this.config = config;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public String adapterType() {
        return TYPE;
    }

    @Override
    public String env() {
        return "SANDBOX";
    }

    @Override
    public boolean asyncCapable() {
        return true;
    }

    @Override
    public ChannelResult pay(ChannelPayRequest request) {
        ChannelResult declined = validateCard(request.getCardNo());
        if (declined != null) {
            return declined;
        }
        return submit(request.getBizNo(), "PAY", request.getAmount(), request.getCardNo(), null);
    }

    @Override
    public ChannelResult refund(ChannelRefundRequest request) {
        ChannelResult declined = validateCard(request.getCardNo());
        if (declined != null) {
            return declined;
        }
        ChannelTradeDO orig = tradeMapper.selectByBizNo(request.getOrigBizNo());
        if (orig == null || !"CAPTURED".equals(orig.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "原渠道交易未扣款，不可退款: " + request.getOrigBizNo());
        }
        return submit(request.getBizNo(), "REFUND", request.getAmount(), request.getCardNo(), request.getOrigBizNo());
    }

    private ChannelResult validateCard(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡号必填");
        }
        if (cardNo.endsWith("0000")) {
            return ChannelResult.declined("渠道拒绝: 银行风控拦截 (卡号尾号 0000)");
        }
        return null;
    }

    private ChannelResult submit(String bizNo, String type, Long amount, String cardNo, String origBizNo) {
        long delay = delayMs();
        if (faults.isDown()) {
            // 宕机注入在同步/异步两种模式下一致：渠道确未受理 → 允许 CH-3 故障切换
            return ChannelResult.notAccepted("沙箱渠道不可达/未受理（故障注入 down）");
        }
        if (!async) {
            // 同步降级模式（默认 stack）：沙箱亦可同步给终态，不产生 WAITING；故障注入仍生效
            sleep(Math.min(delay, 300L));
            if (faults.isReject() && faults.applies(bizNo)) {
                return ChannelResult.declined("沙箱渠道拒绝: 银行风控拦截（故障注入 reject）");
            }
            if (faults.isAmountMismatch() && faults.applies(bizNo)) {
                return ChannelResult.declined("沙箱渠道金额不符: 渠道侧金额与请求不一致（故障注入 amountMismatch）");
            }
            return ChannelResult.success("SBX-" + type + "-" + bizNo);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizNo", bizNo);
        body.put("type", type);
        body.put("amount", amount);
        body.put("cardNo", cardNo);
        body.put("origBizNo", origBizNo);
        ChannelHttpClient.HttpResult response = http.postJson(baseUrl() + "/sandbox-bank/submit",
                write(body), SECRET_REF, timeoutMs());
        if (response.notAccepted() || response.status() == 503) {
            // 确未受理 → 允许 CH-3 故障切换（不会二次受理同一 bizNo）
            return ChannelResult.notAccepted("沙箱渠道不可达/未受理: "
                    + (response.error() == null ? "HTTP 503" : response.error()));
        }
        if (response.ioError() || response.status() >= 500) {
            // 已发送但状态未知：不臆断失败，留 PROCESSING 交查询补单收敛
            return ChannelResult.processing(null, "沙箱渠道受理状态未知（超时/网络异常），待查询补单: "
                    + (response.error() == null ? "HTTP " + response.status() : response.error()));
        }
        if (!response.ok()) {
            return ChannelResult.declined("沙箱渠道受理拒绝: HTTP " + response.status() + " " + response.body());
        }
        JsonNode node = read(response.body());
        String channelOrderNo = node.path("channelOrderNo").asText(null);
        ChannelResult result = ChannelResult.processing(channelOrderNo, "沙箱银行已受理，等待终态回执");
        result.setChannelStatus("SUBMITTED");
        result.setAmount(node.path("amount").isMissingNode() ? amount : node.path("amount").asLong());
        result.setRaw(response.body());
        return result;
    }

    @Override
    public ChannelResult query(String bizNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizNo", bizNo);
        ChannelHttpClient.HttpResult response = http.postJson(baseUrl() + "/sandbox-bank/query",
                write(body), SECRET_REF, timeoutMs());
        if (response.ioError() || !response.ok()) {
            ChannelResult unknown = ChannelResult.processing(null, "沙箱查询不可达（状态未知，不臆断失败）");
            unknown.setChannelStatus("UNKNOWN");
            return unknown;
        }
        JsonNode node = read(response.body());
        String status = node.path("status").asText("NOT_FOUND");
        ChannelResult result = new ChannelResult();
        result.setChannelOrderNo(node.path("channelOrderNo").asText(null));
        result.setChannelStatus(status);
        result.setAmount(node.path("amount").isMissingNode() ? null : node.path("amount").asLong());
        result.setReason(node.path("reason").asText(null));
        result.setRaw(response.body());
        switch (status) {
            case "CAPTURED", "REFUNDED" -> result.setStatus(ChannelResult.SUCCESS);
            case "SUBMITTED" -> result.setStatus(ChannelResult.PROCESSING);
            default -> result.setStatus(ChannelResult.FAILED);
        }
        return result;
    }

    @Override
    public ChannelResult close(String bizNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizNo", bizNo);
        ChannelHttpClient.HttpResult response = http.postJson(baseUrl() + "/sandbox-bank/close",
                write(body), SECRET_REF, timeoutMs());
        if (!response.ok()) {
            return ChannelResult.processing(null, "沙箱关单状态未知: HTTP " + response.status());
        }
        return ChannelResult.success(null);
    }

    /** 回执报文解析（验签由 SandboxReceiptController 用传输层签名头完成，此处只做归一）。 */
    @Override
    public ChannelResult parseReceipt(String rawPayload) {
        JsonNode node = read(rawPayload);
        ChannelResult result = new ChannelResult();
        String status = node.path("status").asText("");
        result.setChannelStatus(status);
        result.setChannelOrderNo(node.path("channelOrderNo").asText(null));
        result.setAmount(node.path("amount").isMissingNode() ? null : node.path("amount").asLong());
        result.setReason(node.path("reason").asText(null));
        result.setRaw(rawPayload);
        switch (status) {
            case "CAPTURED", "REFUNDED" -> result.setStatus(ChannelResult.SUCCESS);
            case "SUBMITTED", "PROCESSING" -> result.setStatus(ChannelResult.PROCESSING);
            default -> result.setStatus(ChannelResult.FAILED);
        }
        return result;
    }

    @Override
    public boolean billSupported() {
        return true;
    }

    @Override
    public ChannelBillFile downloadBill(String bizDate) {
        ChannelHttpClient.HttpResult response = http.get(baseUrl() + "/sandbox-bank/bill?bizDate=" + bizDate,
                SECRET_REF, timeoutMs());
        if (!response.ok()) {
            throw new BizException(ResultCode.DOWNSTREAM_ERROR, "沙箱账单下载失败: HTTP " + response.status()
                    + (response.error() == null ? "" : " " + response.error()));
        }
        String content = response.body();
        long count = 0;
        long sum = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith("#TOTAL,")) {
                String[] cols = line.split(",");
                count = Long.parseLong(cols[1].trim());
                sum = Long.parseLong(cols[2].trim());
            }
        }
        return ChannelBillFile.csv(CODE, bizDate, content, count, sum);
    }

    /** 渠道侧限额（沙箱自身约束，与平台配置取更严者）。 */
    @Override
    public ChannelLimitDTO queryLimit() {
        long single = config.getLong(ChannelConfigKeys.SANDBOX_SINGLE_LIMIT, 0L);
        long daily = config.getLong(ChannelConfigKeys.SANDBOX_DAILY_LIMIT, 0L);
        if (single <= 0 && daily <= 0) {
            return null;
        }
        ChannelLimitDTO dto = new ChannelLimitDTO();
        dto.setChannelCode(CODE);
        dto.setSingleLimit(single > 0 ? single : null);
        dto.setDailyLimit(daily > 0 ? daily : null);
        return dto;
    }

    private String baseUrl() {
        String url = config.get(ChannelConfigKeys.SANDBOX_BASE_URL, ChannelConfigKeys.DEFAULT_SANDBOX_BASE_URL);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------- CH-6 签约/解约 SPI（沙箱边界：本进程内模拟银行侧，无真实银行/支付机构凭据） ----------

    @Override
    public boolean signCapable() {
        return true;
    }

    /**
     * 沙箱签约：卡号规则与 pay 同一口径；渠道协议号确定性派生 {@code SBSIGN-<signNo>}（重复签约幂等）。
     * <b>不连接外部网络、不接真实委托扣款</b>；接生产渠道时同 SPI 替换报文/密钥即可。
     * 代扣资金动作仍走 {@link #agentPay} → {@link #pay}（异步受理 + 回执/查询补单，CH-1 语义不变）。
     */
    @Override
    public ChannelResult sign(String signNo, String cardNo, Long singleLimit) {
        ChannelResult declined = validateCard(cardNo);
        if (declined != null) {
            return declined;
        }
        return ChannelResult.success("SBSIGN-" + signNo);
    }

    @Override
    public ChannelResult unsign(String channelSignNo) {
        return ChannelResult.success(channelSignNo);
    }

    private long delayMs() {
        Long override = faults.getDelayMs();
        return override != null ? override : config.getLong(ChannelConfigKeys.SANDBOX_DELAY_MS,
                ChannelConfigKeys.DEFAULT_SANDBOX_DELAY_MS);
    }

    private long timeoutMs() {
        long injected = faults.getTimeoutMs();
        if (injected > 0) {
            // 注入超时：客户端超时设为注入值的一半，确保读超时先发生（状态未知语义）
            return Math.max(200L, injected / 2);
        }
        return config.getInt(ChannelConfigKeys.SANDBOX_TIMEOUT_MS, ChannelConfigKeys.DEFAULT_SANDBOX_TIMEOUT_MS);
    }

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "渠道报文序列化失败: " + e.getMessage());
        }
    }

    private JsonNode read(String raw) {
        try {
            return json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new BizException(ResultCode.DOWNSTREAM_ERROR, "渠道报文解析失败: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}