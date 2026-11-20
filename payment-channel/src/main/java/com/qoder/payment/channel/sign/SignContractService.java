package com.qoder.payment.channel.sign;

import com.qoder.payment.channel.config.ChannelConfigKeys;
import com.qoder.payment.channel.dataobject.SignContractDO;
import com.qoder.payment.channel.mapper.SignContractMapper;
import com.qoder.payment.channel.mapper.SignDeductMapper;
import com.qoder.payment.channel.spi.ChannelAdapter;
import com.qoder.payment.channel.spi.ChannelAdapterRegistry;
import com.qoder.payment.common.config.DynamicConfig;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.common.secret.SecretCipher;
import com.qoder.payment.common.secret.SecretProvider;
import com.qoder.payment.facade.account.AccountDTO;
import com.qoder.payment.facade.account.AccountFacade;
import com.qoder.payment.facade.account.BankCardDTO;
import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.channel.ChannelResult;
import com.qoder.payment.facade.sign.SignContractDTO;
import com.qoder.payment.facade.sign.SignQuotaDTO;
import com.qoder.payment.facade.sign.SignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CH-6 签约协议生命周期 + X-4 账户授权视图（channel 域，与 channel_trade 同库同事务边界）。
 *
 * <p><b>状态机</b>：{@code SIGNED → CANCELLED}（解约/账户侧撤销）/ {@code SIGNED → EXPIRED}（到期作业）；
 * 终态不可逆。同三元组（accountId+merchantId+channelCode）最多一份有效协议由
 * {@code sign_contract.active_key} UNIQUE 结构性保证（并发签约不重复）。
 *
 * <p><b>受理前拒绝不落行</b>：渠道不支持签约/渠道拒绝/卡号非法/账户冻结/卡已解绑/参数非法
 * 一律在插入协议行之前抛出，只留 sign_audit 痕。
 *
 * <p><b>卡号保护</b>：明文只在入参与 SPI 调用链内；落库 hash(SHA-256) + mask(后四位) +
 * AES-GCM 密文（主密钥经 {@link SecretProvider}，缺失 fail-fast 拒绝签约），出参绝不含明文/密文。
 *
 * <p><b>只调其他域 facade 既有方法</b>：{@code AccountFacade.get/listCards/checkLimit}（A-2/A-3/M-3），
 * 不给 AccountFacade 新增方法；账户侧授权视图由本域按 accountId 聚合提供（X-4 归属决策）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignContractService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final long DAY_MS = 86_400_000L;

    private final SignContractMapper contractMapper;
    private final SignDeductMapper deductMapper;
    private final SignAuditService audit;
    private final SignEventPublisher events;
    private final ChannelAdapterRegistry registry;
    private final DynamicConfig config;
    private final SecretProvider secrets;
    private final SignTx tx;

    @DubboReference(check = false, timeout = 5000, retries = 0)
    private AccountFacade accountFacade;

    // ================= 签约 =================

    public SignContractDTO sign(SignRequest request) {
        requireEnabled();
        if (request == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "签约请求不可为空");
        }
        String accountId = trim(request.getAccountId());
        String merchantId = trim(request.getMerchantId());
        String cardNo = trim(request.getCardNo());
        String requestId = trim(request.getRequestId());
        if (accountId == null || merchantId == null || cardNo == null || requestId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "签约必填 accountId/merchantId/cardNo/requestId");
        }
        if (!BankCardUtil.validCardNo(cardNo)) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡号格式非法（12-19 位数字且 Luhn 校验通过）");
        }
        String channelCode = trim(request.getChannelCode()) == null
                ? config.get(ChannelConfigKeys.ROUTE_DEFAULT_CHANNEL, ChannelConfigKeys.DEFAULT_ROUTE_CHANNEL)
                : trim(request.getChannelCode());
        ChannelAdapter adapter = registry.resolve(channelCode);
        if (!adapter.signCapable()) {
            throw new BizException(ResultCode.BIZ_ERROR, "渠道不支持签约（CH-6 signCapable=false）: " + channelCode);
        }

        // 幂等①：同 requestId 重复签约返回既有协议
        SignContractDO byRequest = contractMapper.selectByRequestId(requestId);
        if (byRequest != null) {
            audit.writeQuietly(byRequest.getSignNo(), null, "SIGN_REPLAY", request.getOperator(), requestId,
                    null, "OK", "requestId 幂等命中");
            return toDTO(byRequest, true);
        }
        // 幂等②：同 (accountId,merchantId,channelCode) 已有有效协议（计划幂等键 SIGN 三元组）
        SignContractDO active = contractMapper.selectActive(accountId, merchantId, channelCode);
        if (active != null) {
            audit.writeQuietly(active.getSignNo(), null, "SIGN_REPLAY", request.getOperator(), requestId,
                    null, "OK", "同三元组已有有效协议");
            return toDTO(active, true);
        }

        // 账户与绑卡校验（只调 account 域既有 facade）
        AccountDTO account = accountFacade.get(accountId).orThrow();
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "账户非 ACTIVE，不可签约: " + accountId);
        }
        String mask = BankCardUtil.maskCard(cardNo);
        List<BankCardDTO> cards = orEmpty(accountFacade.listCards(accountId));
        for (BankCardDTO card : cards) {
            if (mask.equals(card.getCardNoMask()) && "UNBOUND".equals(card.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR, "银行卡已解绑，不可签约（A-2 联动）: " + mask);
            }
        }

        long now = System.currentTimeMillis();
        long singleLimit = request.getSingleLimit() != null ? request.getSingleLimit()
                : config.getLong(SignConfigKeys.DEFAULT_SINGLE_LIMIT, SignConfigKeys.DEFAULT_SINGLE_LIMIT_VALUE);
        long periodLimit = request.getPeriodLimit() != null ? request.getPeriodLimit()
                : config.getLong(SignConfigKeys.DEFAULT_PERIOD_LIMIT, SignConfigKeys.DEFAULT_PERIOD_LIMIT_VALUE);
        String periodType = periodType(request.getPeriodType());
        long effectiveAt = request.getEffectiveAt() != null ? request.getEffectiveAt() : now;
        int validDays = config.getInt(SignConfigKeys.DEFAULT_VALID_DAYS, SignConfigKeys.DEFAULT_VALID_DAYS_VALUE);
        long expiredAt = request.getExpiredAt() != null ? request.getExpiredAt() : effectiveAt + validDays * DAY_MS;
        int maxRetry = request.getMaxRetry() != null ? request.getMaxRetry()
                : config.getInt(SignConfigKeys.DEFAULT_MAX_RETRY, SignConfigKeys.DEFAULT_MAX_RETRY_VALUE);
        long retryIntervalMs = request.getRetryIntervalMs() != null ? request.getRetryIntervalMs()
                : config.getLong(SignConfigKeys.DEFAULT_RETRY_INTERVAL_MS, SignConfigKeys.DEFAULT_RETRY_INTERVAL_MS_VALUE);
        if (singleLimit <= 0 || periodLimit <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "授权限额必须为正（分）");
        }
        if (expiredAt <= effectiveAt) {
            throw new BizException(ResultCode.PARAM_ERROR, "协议失效时间必须晚于生效时间");
        }
        Integer deductDay = request.getDeductDay();
        if (deductDay != null && (deductDay < 1 || deductDay > 28)) {
            throw new BizException(ResultCode.PARAM_ERROR, "扣款日必须在 1-28 之间");
        }
        if (maxRetry < 0 || retryIntervalMs < 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "重试策略非法（maxRetry/retryIntervalMs 不可为负）");
        }

        // 渠道签约 SPI（受理前拒绝不落协议行）
        String signNo = "SG" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ChannelResult result = adapter.sign(signNo, cardNo, singleLimit);
        if (result == null || !result.isSuccess()) {
            String reason = result == null ? "渠道无响应" : result.getReason();
            audit.writeQuietly(signNo, null, "REJECT", request.getOperator(), requestId, null, "REJECT",
                    "渠道签约拒绝: " + reason);
            throw new BizException(ResultCode.BIZ_ERROR, "渠道签约拒绝: " + reason);
        }

        String masterKey = secrets.getSecret(SecretCipher.MASTER_KEY_REF);
        if (masterKey == null || masterKey.length() < 16) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "卡号加密主密钥缺失（SecretProvider），拒绝签约落库");
        }
        SignContractDO row = new SignContractDO();
        row.setSignNo(signNo);
        row.setAccountId(accountId);
        row.setMerchantId(merchantId);
        row.setChannelCode(channelCode);
        row.setChannelSignNo(result.getChannelOrderNo());
        row.setCardNoHash(BankCardUtil.sha256(cardNo));
        row.setCardNoMask(mask);
        row.setCardNoCipher(SecretCipher.encrypt(masterKey, cardNo));
        row.setStatus("SIGNED");
        row.setSingleLimit(singleLimit);
        row.setPeriodLimit(periodLimit);
        row.setPeriodType(periodType);
        row.setEffectiveAt(effectiveAt);
        row.setExpiredAt(expiredAt);
        row.setDeductDay(deductDay);
        row.setMaxRetry(maxRetry);
        row.setRetryIntervalMs(retryIntervalMs);
        row.setCallbackUrl(trim(request.getCallbackUrl()));
        row.setAppId(trim(request.getAppId()));
        row.setRequestId(requestId);
        row.setAuthorizedScope(scopeJson(request, singleLimit, periodLimit, periodType, deductDay));
        row.setActiveKey(activeKey(accountId, merchantId, channelCode));
        row.setSignedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        Map<String, Object> payload = eventPayload(row);
        try {
            tx.run(() -> {
                contractMapper.insert(row);
                audit.write(signNo, null, "SIGN", request.getOperator(), requestId, null, "OK",
                        "签约成功 channel=" + channelCode + " singleLimit=" + singleLimit + " periodLimit=" + periodLimit);
                events.enqueue(SignEventPublisher.SIGN_SUCCESS, signNo, null, payload);
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发签约：active_key UNIQUE / request_id UNIQUE 结构性去重 → 返回既有有效协议
            SignContractDO winner = contractMapper.selectActive(accountId, merchantId, channelCode);
            if (winner == null) {
                winner = contractMapper.selectByRequestId(requestId);
            }
            if (winner != null) {
                log.info("CH-6 签约并发幂等命中 signNo={} requestId={}", winner.getSignNo(), requestId);
                return toDTO(winner, true);
            }
            throw e;
        }
        // 事务提交后投递（notify 不可达留 outbox 痕，作业兜底）
        events.dispatchSign(signNo);
        log.info("CH-6 签约成功 signNo={} accountId={} merchantId={} channel={} singleLimit={} periodLimit={} "
                        + "periodType={} deductDay={} expiredAt={}", signNo, accountId, merchantId, channelCode,
                singleLimit, periodLimit, periodType, deductDay, expiredAt);
        return toDTO(row, false);
    }

    // ================= 解约 =================

    public SignContractDTO unsign(String signNo, String requestId, String operator, String reason) {
        requireEnabled();
        SignContractDO contract = require(signNo);
        if ("CANCELLED".equals(contract.getStatus())) {
            return toDTO(contract, true);
        }
        if (!"SIGNED".equals(contract.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "协议状态不可解约: " + contract.getStatus());
        }
        ChannelAdapter adapter = registry.resolve(contract.getChannelCode());
        ChannelResult result = adapter.unsign(contract.getChannelSignNo());
        if (result == null || !result.isSuccess()) {
            String message = result == null ? "渠道无响应" : result.getReason();
            audit.writeQuietly(signNo, null, "UNSIGN", operator, requestId, null, "FAIL", message);
            throw new BizException(ResultCode.BIZ_ERROR, "渠道解约失败: " + message);
        }
        long now = System.currentTimeMillis();
        String cancelReason = reason == null || reason.isBlank() ? "用户/商户解约" : reason;
        Map<String, Object> payload = eventPayload(contract);
        payload.put("cancelledAt", now);
        payload.put("reason", cancelReason);
        boolean[] changed = {false};
        tx.run(() -> {
            int updated = contractMapper.casCancel(signNo, now, cancelReason, now);
            if (updated == 0) {
                return;
            }
            changed[0] = true;
            audit.write(signNo, null, "UNSIGN", operator, requestId, null, "OK", cancelReason);
            events.enqueue(SignEventPublisher.SIGN_CANCEL, signNo, null, payload);
        });
        if (changed[0]) {
            events.dispatchSign(signNo);
            log.info("CH-6 解约成功 signNo={} reason={}（在途扣款单不打断，由既有支付路径收敛）", signNo, cancelReason);
        }
        return toDTO(require(signNo), !changed[0]);
    }

    // ================= 查询 / 额度视图 =================

    public SignContractDTO get(String signNo) {
        return toDTO(require(signNo), false);
    }

    public List<SignContractDTO> list(String accountId, String merchantId, String status) {
        List<SignContractDTO> out = new ArrayList<>();
        for (SignContractDO row : contractMapper.selectList(trim(accountId), trim(merchantId), trim(status),
                SignConfigKeys.LIST_LIMIT)) {
            out.add(toDTO(row, false));
        }
        return out;
    }

    public SignQuotaDTO quota(String signNo) {
        SignContractDO contract = require(signNo);
        long now = System.currentTimeMillis();
        String periodKey = periodKey(contract.getPeriodType(), now);
        long used = deductMapper.sumPeriod(signNo, periodKey);
        int count = deductMapper.countPeriod(signNo, periodKey);
        SignQuotaDTO dto = new SignQuotaDTO();
        dto.setSignNo(signNo);
        dto.setStatus(contract.getStatus());
        dto.setSingleLimit(contract.getSingleLimit());
        dto.setPeriodLimit(contract.getPeriodLimit());
        dto.setPeriodType(contract.getPeriodType());
        dto.setPeriodKey(periodKey);
        dto.setPeriodUsed(used);
        dto.setPeriodRemaining(contract.getPeriodLimit() == null ? null
                : Math.max(0L, contract.getPeriodLimit() - used));
        dto.setPeriodCount(count);
        dto.setEffectiveAt(contract.getEffectiveAt());
        dto.setExpiredAt(contract.getExpiredAt());
        dto.setInValidPeriod(now >= contract.getEffectiveAt() && now < contract.getExpiredAt());
        dto.setDeductDay(contract.getDeductDay());
        int today = LocalDate.now(ZONE).getDayOfMonth();
        dto.setToday(today);
        dto.setDeductDayMatch(contract.getDeductDay() == null || contract.getDeductDay() == today);
        dto.setMaxRetry(contract.getMaxRetry());
        dto.setRetryIntervalMs(contract.getRetryIntervalMs());
        return dto;
    }

    // ================= X-4 账户侧授权管理 =================

    /** 账户已签协议视图（三方关联 accountId + merchantId + signNo）。 */
    public List<SignContractDTO> authorizations(String accountId) {
        if (trim(accountId) == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        accountFacade.get(accountId).orThrow();
        List<SignContractDTO> out = new ArrayList<>();
        for (SignContractDO row : contractMapper.selectByAccount(accountId, SignConfigKeys.LIST_LIMIT)) {
            out.add(toDTO(row, false));
        }
        return out;
    }

    /** 账户侧撤销授权：归属校验（越权拒绝）后复用解约同一状态机与事件投递。 */
    public SignContractDTO revoke(String accountId, String signNo, String requestId, String operator) {
        SignContractDO contract = require(signNo);
        if (trim(accountId) == null || !accountId.equals(contract.getAccountId())) {
            audit.writeQuietly(signNo, null, "REVOKE", operator, requestId, null, "REJECT",
                    "协议不属于账户 " + accountId + "（越权撤销拒绝）");
            throw new BizException(ResultCode.FORBIDDEN, "协议不属于该账户，不可撤销授权: " + signNo);
        }
        audit.writeQuietly(signNo, null, "REVOKE", operator, requestId, null, "OK", "账户侧撤销授权");
        return unsign(signNo, requestId, operator, "账户侧撤销授权");
    }

    /** 到期协议置 EXPIRED（作业入口，CAS 幂等）。返回置位笔数。 */
    public int expireDue(int limit) {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (SignContractDO row : contractMapper.selectExpired(now, Math.max(1, limit))) {
            int updated = contractMapper.casExpire(row.getSignNo(), now, now);
            if (updated > 0) {
                expired++;
                audit.writeQuietly(row.getSignNo(), null, "EXPIRE", "system", null, null, "OK",
                        "协议到期置 EXPIRED expiredAt=" + row.getExpiredAt());
                log.info("CH-6 协议到期 signNo={} expiredAt={}", row.getSignNo(), row.getExpiredAt());
            }
        }
        return expired;
    }

    // ================= 代扣授权校验（供 SignDeductService 复用，同一口径） =================

    /**
     * 授权校验（受理前，全部通过才允许建单）：状态/有效期/商户范围/单笔限额/扣款日。
     * 任何一项不通过 → 抛 BizException，调用方不得落扣款单/交易单/支付单。
     */
    public void assertDeductable(SignContractDO contract, long amount, String merchantId, boolean checkDeductDay) {
        long now = System.currentTimeMillis();
        if (!"SIGNED".equals(contract.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "协议不可代扣（状态 " + contract.getStatus() + "）: "
                    + contract.getSignNo());
        }
        if (now < contract.getEffectiveAt()) {
            throw new BizException(ResultCode.BIZ_ERROR, "协议尚未生效: " + contract.getSignNo());
        }
        if (now >= contract.getExpiredAt()) {
            throw new BizException(ResultCode.BIZ_ERROR, "协议已过期，不可代扣: " + contract.getSignNo());
        }
        if (merchantId != null && !merchantId.isBlank() && !merchantId.equals(contract.getMerchantId())) {
            throw new BizException(ResultCode.FORBIDDEN, "商户不在授权范围内: " + merchantId);
        }
        if (contract.getSingleLimit() != null && amount > contract.getSingleLimit()) {
            throw new BizException(ResultCode.BIZ_ERROR, "代扣金额超单笔授权限额: amount=" + amount
                    + " singleLimit=" + contract.getSingleLimit());
        }
        if (checkDeductDay && contract.getDeductDay() != null
                && contract.getDeductDay() != LocalDate.now(ZONE).getDayOfMonth()) {
            throw new BizException(ResultCode.BIZ_ERROR, "今日非约定扣款日（deductDay=" + contract.getDeductDay()
                    + " today=" + LocalDate.now(ZONE).getDayOfMonth() + "）");
        }
    }

    /** 周期累计额度校验（必须在协议行锁内调用，串行化同协议并发代扣）。 */
    public void assertPeriodQuota(SignContractDO locked, long amount) {
        if (locked.getPeriodLimit() == null || "NONE".equals(locked.getPeriodType())) {
            return;
        }
        String key = periodKey(locked.getPeriodType(), System.currentTimeMillis());
        long used = deductMapper.sumPeriod(locked.getSignNo(), key);
        if (used + amount > locked.getPeriodLimit()) {
            throw new BizException(ResultCode.BIZ_ERROR, "代扣超周期累计授权限额: period=" + key + " used=" + used
                    + " amount=" + amount + " periodLimit=" + locked.getPeriodLimit());
        }
    }

    /** 卡号明文还原（只在代扣受理调用链内，绝不落日志/出参）。 */
    public String cardPlain(SignContractDO contract) {
        String masterKey = secrets.getSecret(SecretCipher.MASTER_KEY_REF);
        if (masterKey == null || masterKey.length() < 16) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "卡号解密主密钥缺失，拒绝代扣");
        }
        return SecretCipher.decrypt(masterKey, contract.getCardNoCipher());
    }

    public SignContractDO require(String signNo) {
        if (signNo == null || signNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "signNo 必填");
        }
        SignContractDO contract = contractMapper.selectBySignNo(signNo.trim());
        if (contract == null) {
            throw new BizException(ResultCode.NOT_FOUND, "签约协议不存在: " + signNo);
        }
        return contract;
    }

    public void requireEnabled() {
        if (!config.getBool(SignConfigKeys.ENABLED, SignConfigKeys.DEFAULT_ENABLED)) {
            throw new BizException(ResultCode.DEGRADED, "签约代扣已关闭（channel.sign.enabled=false）");
        }
    }

    // ================= 工具 =================

    public static String periodKey(String periodType, long now) {
        if ("NONE".equals(periodType)) {
            return "ALL";
        }
        LocalDate date = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        return "MONTH".equals(periodType) ? date.format(MONTH) : date.format(DAY);
    }

    public static String activeKey(String accountId, String merchantId, String channelCode) {
        return accountId + ":" + merchantId + ":" + channelCode;
    }

    private String periodType(String raw) {
        String value = trim(raw);
        if (value == null) {
            value = config.get(SignConfigKeys.DEFAULT_PERIOD_TYPE, SignConfigKeys.DEFAULT_PERIOD_TYPE_VALUE);
        }
        value = value.toUpperCase();
        if (!"DAY".equals(value) && !"MONTH".equals(value) && !"NONE".equals(value)) {
            throw new BizException(ResultCode.PARAM_ERROR, "periodType 仅支持 DAY/MONTH/NONE");
        }
        return value;
    }

    private Map<String, Object> eventPayload(SignContractDO contract) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // agreementNo 为 N-3 SIGN_SUCCESS/SIGN_CANCEL 模板必填字段
        payload.put("agreementNo", contract.getSignNo());
        payload.put("signNo", contract.getSignNo());
        payload.put("accountId", contract.getAccountId());
        payload.put("merchantId", contract.getMerchantId());
        payload.put("channelCode", contract.getChannelCode());
        payload.put("status", contract.getStatus());
        payload.put("singleLimit", contract.getSingleLimit());
        payload.put("periodLimit", contract.getPeriodLimit());
        payload.put("periodType", contract.getPeriodType());
        payload.put("expiredAt", contract.getExpiredAt());
        payload.put("cardNoMask", contract.getCardNoMask());
        return payload;
    }

    private String scopeJson(SignRequest request, long singleLimit, long periodLimit, String periodType,
                             Integer deductDay) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("merchantId", request.getMerchantId());
        scope.put("singleLimit", singleLimit);
        scope.put("periodLimit", periodLimit);
        scope.put("periodType", periodType);
        scope.put("deductDay", deductDay);
        scope.put("note", request.getScopeNote());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(scope);
        } catch (Exception e) {
            return "{\"merchantId\":\"" + request.getMerchantId() + "\"}";
        }
    }

    private static <T> List<T> orEmpty(com.qoder.payment.common.result.Result<List<T>> result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return List.of();
        }
        return result.getData();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static SignContractDTO toDTO(SignContractDO row, boolean repeated) {
        SignContractDTO dto = new SignContractDTO();
        BeanUtils.copyProperties(row, dto);
        dto.setRepeated(repeated);
        return dto;
    }
}