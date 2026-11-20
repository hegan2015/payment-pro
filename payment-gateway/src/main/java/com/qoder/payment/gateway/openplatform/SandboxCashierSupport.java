package com.qoder.payment.gateway.openplatform;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.openplatform.OpenPlatformFacade;
import com.qoder.payment.facade.openplatform.SandboxAccountDTO;
import com.qoder.payment.facade.openplatform.SandboxOrderDTO;
import com.qoder.payment.facade.openplatform.SandboxTxnDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * C-5 多端收银台 + CH-6 签约代扣的<b>沙箱受理层</b>（G-4a 沙箱隔离的 additive 扩展）。
 *
 * <p><b>为什么在网关而不在 cashier/channel 域</b>：沙箱 appId 的请求在 {@link SandboxRouter} 就被截流，
 * 结构上不进生产域；本类只调 {@link OpenPlatformFacade} <b>既有</b>沙箱账本方法
 * （开户/余额/资金单/划转/下单/支付/查单），<b>不给任何域 facade 新增方法</b>、不新增表、不改库状态。
 *
 * <p><b>隔离证据</b>：资金只落 member 库 openapi_sandbox_account/_txn/_order（id 前缀 SBXA/SBXO/SBXP，
 * fee=0、同步 SUCCESS）；付款码/口令哈希/短信码/验证会话/签约协议/扣款单为<b>按 appId 隔离的进程内沙箱态</b>
 * （联调用，重启即清；生产路径由 cashier/channel 域落库）。生产账户/支付单/账务一行不动。
 *
 * <p><b>形状对齐</b>：出参与生产 OpenAPI 逐字段同名（CashierDTO / PayCodeDTO / VerifyResultDTO /
 * PayResultDTO{payment,repeated} / SignContractDTO / SignDeductDTO），业务方同一套渲染代码可切沙箱/生产。
 * 主扫 {@code qrContent} 同样是扫码 URL（{@code .../scan?code=<token>&amount=<amt>}），
 * 接入方须用 SDK 的 {@code normalizeScanCode}/{@code normalize_scan_code} 归一取 token。
 *
 * <p><b>拒绝在受理前</b>：验证失败/码过期/已核销/协议失效/超额度一律先拒，
 * 不建沙箱订单与资金单（不落悬挂单）；沙箱拒绝只记日志（生产路径落 sign_audit / pay_verify）。
 */
@Slf4j
@Component
public class SandboxCashierSupport {

    /** 沙箱支持的收银台 + 签约方法（版本公告/契约自检的单一事实来源之一）。 */
    public static final List<String> METHODS = List.of(
            "cashier.methods", "cashier.code.create", "cashier.code.parse", "cashier.code.query",
            "cashier.scanPay", "cashier.verify", "cashier.payVerified", "cashier.payPassword.set",
            "cashier.sms.send", "channel.sign", "channel.agent.pay");

    private static final long FREE_LIMIT = 1000L;
    private static final long CODE_TTL_MS = 60_000L;
    private static final long SESSION_TTL_MS = 300_000L;
    private static final long SMS_TTL_MS = 300_000L;
    private static final long LOCK_MS = 15 * 60_000L;
    private static final int MAX_FAIL = 5;
    private static final long DEFAULT_SINGLE_LIMIT = 50_000L;
    private static final long DEFAULT_PERIOD_LIMIT = 200_000L;
    private static final long DEFAULT_VALID_MS = 365L * 24 * 3600 * 1000;
    private static final String QR_BASE = "https://cashier.payment-pro.example/scan";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong SEQ = new AtomicLong();

    @DubboReference
    private OpenPlatformFacade openPlatformFacade;

    private final Map<String, AppState> states = new ConcurrentHashMap<>();

    /** 沙箱受理入口（验签/nonce 已由 SandboxRouter 完成）。 */
    public Result<Object> handle(String appId, String api, Map<String, Object> body) {
        AppState state = states.computeIfAbsent(appId, key -> new AppState());
        return switch (api) {
            case "cashier.methods" -> Result.ok(methods(appId, state, body));
            case "cashier.code.create" -> Result.ok(createCode(appId, state, body));
            case "cashier.code.parse", "cashier.code.query" -> Result.ok(codeDto(state, lookupCode(state, body)));
            case "cashier.scanPay" -> Result.ok(scanPay(appId, state, body));
            case "cashier.verify" -> Result.ok(verify(appId, state, body));
            case "cashier.payVerified" -> Result.ok(payVerified(appId, state, body));
            case "cashier.payPassword.set" -> Result.ok(setPayPassword(appId, state, body));
            case "cashier.sms.send" -> Result.ok(sendSms(appId, state, body));
            case "channel.sign" -> Result.ok(sign(appId, state, body));
            case "channel.agent.pay" -> Result.ok(agentPay(appId, state, body));
            default -> throw new BizException("A0400", "沙箱环境暂不支持该方法: " + api);
        };
    }

    // ------------------------------------------------------------------
    // C-1 支付方式渲染
    // ------------------------------------------------------------------

    private Map<String, Object> methods(String appId, AppState state, Map<String, Object> body) {
        SandboxOrderDTO order = order(appId, required(body, "orderId"));
        String accountId = opt(body, "accountId");
        List<SandboxAccountDTO> all = openPlatformFacade.sandboxListAccounts(appId).orThrow();
        List<SandboxAccountDTO> payers = new ArrayList<>();
        if (accountId != null) {
            payers.add(all.stream().filter(a -> accountId.equals(a.getAccountId())).findFirst()
                    .orElseThrow(() -> new BizException("A0404", "沙箱账户不存在: " + accountId)));
        } else {
            all.stream().filter(a -> "USER".equals(a.getType())).forEach(payers::add);
        }
        long amount = order.getAmount();
        boolean enough = payers.stream().anyMatch(a -> a.getBalance() != null && a.getBalance() >= amount);
        List<Map<String, Object>> methods = new ArrayList<>();
        methods.add(method("BALANCE", "余额", "沙箱余额支付（同步终态，fee=0）", accountMaps(payers),
                enough, enough ? null : "沙箱账户余额不足", "REAL", "wallet"));
        methods.add(method("CARD", "银行卡", "沙箱不接真实渠道", List.of(), false,
                "沙箱仅支持 BALANCE（无渠道调用）", "REAL", "card"));
        methods.add(method("PAY_CODE", "付款码", "被扫：用户出示 18 位码", List.of(), true, null, "REAL", "barcode"));
        methods.add(method("SCAN", "扫一扫", "主扫：商户出二维码，用户扫", List.of(), true, null, "REAL", "qrcode"));
        for (String reserved : List.of("QUICK_PAY", "NET_BANK", "CREDIT", "COUPON", "COMBO")) {
            methods.add(method(reserved, reserved, "预留方式", List.of(), false,
                    "沙箱未开通该支付方式", "RESERVED", "dot"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", orderMap(order));
        data.put("methods", methods);
        data.put("merchantName", "沙箱商户-" + order.getMerchantId());
        data.put("discount", 0L);
        data.put("actualAmount", amount);
        data.put("freeVerifyLimit", FREE_LIMIT);
        data.put("currency", "CNY");
        data.put("sandbox", true);
        return data;
    }

    private static Map<String, Object> method(String code, String name, String hint,
                                              List<Map<String, Object>> accounts, boolean available,
                                              String reason, String category, String icon) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", code);
        map.put("name", name);
        map.put("hint", hint);
        map.put("accounts", accounts);
        map.put("available", available);
        map.put("unavailableReason", reason);
        map.put("limit", 500_000L);
        map.put("category", category);
        map.put("maintenance", false);
        map.put("enabled", true);
        map.put("icon", icon);
        return map;
    }

    private static List<Map<String, Object>> accountMaps(List<SandboxAccountDTO> accounts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SandboxAccountDTO account : accounts) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", account.getAccountId());
            map.put("memberId", account.getMemberId());
            map.put("memberName", account.getName());
            map.put("type", account.getType());
            map.put("status", "ACTIVE");
            map.put("balance", account.getBalance());
            list.add(map);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // C-2 付款码 / 扫码
    // ------------------------------------------------------------------

    private Map<String, Object> createCode(String appId, AppState state, Map<String, Object> body) {
        String type = required(body, "type").toUpperCase();
        if (!type.equals("USER_PRESENTED") && !type.equals("MERCHANT_PRESENTED")) {
            throw new BizException("A0400", "type 须为 USER_PRESENTED/MERCHANT_PRESENTED");
        }
        String requestId = opt(body, "requestId");
        synchronized (state) {
            if (requestId != null) {
                String priorCode = state.codeByRequest.get(requestId);
                Code prior = priorCode == null ? null : state.codes.get(priorCode);
                if (prior != null && "ACTIVE".equals(prior.status) && prior.expireAt > System.currentTimeMillis()) {
                    return codeDto(state, prior);
                }
            }
            String orderId = opt(body, "orderId");
            Long amount = longOpt(body, "amount");
            String merchantName = null;
            String subject = null;
            if (orderId != null) {
                SandboxOrderDTO order = order(appId, orderId);
                amount = order.getAmount();
                merchantName = "沙箱商户-" + order.getMerchantId();
                subject = order.getSubject();
            } else if (amount == null || amount <= 0) {
                throw new BizException("A0400", "orderId/amount 至少一项必填且金额>0");
            }
            String accountId = opt(body, "accountId");
            if (type.equals("USER_PRESENTED") && accountId == null) {
                throw new BizException("A0400", "被扫码须指定付款账户 accountId");
            }
            if (accountId != null) {
                openPlatformFacade.sandboxBalance(appId, accountId).orThrow();
            }
            long now = System.currentTimeMillis();
            Code code = new Code();
            code.type = type;
            code.accountId = accountId;
            code.orderId = orderId;
            code.amount = amount;
            code.status = "ACTIVE";
            code.createdAt = now;
            code.expireAt = now + CODE_TTL_MS;
            code.requestId = requestId;
            code.merchantName = merchantName;
            code.subject = subject;
            if (type.equals("USER_PRESENTED")) {
                code.code = uniqueNumericCode(state);
                code.qrContent = code.code;
                code.displayCode = group(code.code);
            } else {
                code.code = "MP" + UUID.randomUUID().toString().replace("-", "");
                // 与生产同口径：qrContent 是扫码 URL，接入方须从 code= 归一取 token
                code.qrContent = QR_BASE + "?code=" + code.code + "&amount=" + amount;
                code.displayCode = code.code;
            }
            state.codes.put(code.code, code);
            if (requestId != null) {
                state.codeByRequest.put(requestId, code.code);
            }
            log.info("沙箱生成{}码 appId={} orderId={} amount={}", type, appId, orderId, amount);
            return codeDto(state, code);
        }
    }

    private Code lookupCode(AppState state, Map<String, Object> body) {
        String value = required(body, "code");
        Code code = state.codes.get(value);
        if (code == null) {
            throw new BizException("A0404", "付款码不存在");
        }
        synchronized (state) {
            if ("ACTIVE".equals(code.status) && code.expireAt <= System.currentTimeMillis()) {
                code.status = "EXPIRED";
            }
        }
        return code;
    }

    private static Map<String, Object> codeDto(AppState state, Code code) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code.code);
        map.put("type", code.type);
        map.put("accountId", code.accountId);
        map.put("orderId", code.orderId);
        map.put("amount", code.amount);
        map.put("status", code.status);
        map.put("expireAt", code.expireAt);
        map.put("createdAt", code.createdAt);
        map.put("consumedAt", code.consumedAt);
        map.put("qrContent", code.qrContent);
        map.put("displayCode", code.displayCode);
        map.put("merchantName", code.merchantName);
        map.put("subject", code.subject);
        map.put("ttlSeconds", Math.max(0L, (code.expireAt - System.currentTimeMillis()) / 1000));
        return map;
    }

    private Map<String, Object> scanPay(String appId, AppState state, Map<String, Object> body) {
        String codeValue = required(body, "code");
        String requestId = required(body, "requestId");
        synchronized (state) {
            Map<String, Object> prior = state.payResults.get(requestId);
            if (prior != null) {
                return repeated(prior);
            }
            Code code = state.codes.get(codeValue);
            if (code == null) {
                throw new BizException("A0404", "付款码不存在");
            }
            long now = System.currentTimeMillis();
            if ("ACTIVE".equals(code.status) && code.expireAt <= now) {
                code.status = "EXPIRED";
            }
            if ("EXPIRED".equals(code.status)) {
                throw new BizException("A0409", "付款码已过期");
            }
            if ("CONSUMED".equals(code.status)) {
                throw new BizException("A0409", "付款码已核销（防重放）");
            }
            if (code.orderId == null) {
                throw new BizException("A0400", "码未绑定订单，不可受理（定额码仅供展示）");
            }
            String payer = "MERCHANT_PRESENTED".equals(code.type)
                    ? required(body, "payerAccountId") : code.accountId;
            SandboxOrderDTO order = order(appId, code.orderId);
            String method = normalizeMethod(opt(body, "method"));
            requireBalance(method);
            verifyGate(appId, state, payer, code.orderId, order.getAmount(),
                    opt(body, "verifyToken"), opt(body, "payPassword"));
            assertOrderNotPaid(state, code.orderId, requestId);
            code.status = "CONSUMED";
            code.consumedAt = now;
            code.consumedRequestId = requestId;
            return accept(appId, state, code.orderId, payer, requestId, order.getAmount(), opt(body, "cardNo"));
        }
    }

    // ------------------------------------------------------------------
    // C-3 支付验证 / 验证前置受理
    // ------------------------------------------------------------------

    private Map<String, Object> verify(String appId, AppState state, Map<String, Object> body) {
        String accountId = identity(appId, body);
        String orderId = opt(body, "orderId");
        Long amount = longOpt(body, "amount");
        long value = amount == null ? 0L : amount;
        String type = opt(body, "verifyType") == null ? "PASSWORD" : opt(body, "verifyType").toUpperCase();
        String requestId = opt(body, "requestId");
        synchronized (state) {
            if (requestId != null) {
                String priorToken = state.sessionByRequest.get(requestId);
                Session prior = priorToken == null ? null : state.sessions.get(priorToken);
                if (prior != null && !prior.used && prior.expireAt > System.currentTimeMillis()) {
                    return passResult(prior);
                }
            }
            Credential credential = state.credentials.get(accountId);
            long now = System.currentTimeMillis();
            switch (type) {
                case "SMALL_AMOUNT_FREE":
                    if (value <= FREE_LIMIT) {
                        return issue(state, accountId, orderId, value, "SMALL_AMOUNT_FREE", requestId);
                    }
                    return fail("PASSWORD", "超出小额免密限额，需支付验证", null, null);
                case "BIO":
                    if (opt(body, "bioToken") == null) {
                        return fail("BIO", "未采集到生物识别令牌（沙箱模拟）", null, null);
                    }
                    return issue(state, accountId, orderId, value, "BIO", requestId);
                case "SMS": {
                    Sms sms = state.sms.get(accountId);
                    if (sms == null || sms.expireAt <= now || sms.used) {
                        return fail("SMS", "请先获取短信验证码（沙箱）", null, null);
                    }
                    if (!sms.code.equals(opt(body, "smsCode"))) {
                        return fail("SMS", "短信验证码错误", null, null);
                    }
                    sms.used = true;
                    return issue(state, accountId, orderId, value, "SMS", requestId);
                }
                case "PASSWORD":
                default: {
                    if (credential == null) {
                        return fail("NEED_SET_PASSWORD", "请先设置支付密码", null, null);
                    }
                    if (credential.lockedUntil != null && credential.lockedUntil > now) {
                        return fail("LOCKED", "支付密码错误次数过多，已锁定", 0, credential.lockedUntil);
                    }
                    String password = opt(body, "payPassword");
                    if (password != null && hash(password, credential.salt).equals(credential.hash)) {
                        credential.failCount = 0;
                        credential.lockedUntil = null;
                        return issue(state, accountId, orderId, value, "PASSWORD", requestId);
                    }
                    credential.failCount++;
                    Long lockedUntil = null;
                    if (credential.failCount >= MAX_FAIL) {
                        lockedUntil = now + LOCK_MS;
                        credential.lockedUntil = lockedUntil;
                    }
                    int remain = Math.max(0, MAX_FAIL - credential.failCount);
                    return fail(lockedUntil != null ? "LOCKED" : "PASSWORD",
                            lockedUntil != null ? "支付密码错误次数过多，已锁定 15 分钟"
                                    : "支付密码错误（剩余 " + remain + " 次）", remain, lockedUntil);
                }
            }
        }
    }

    private Map<String, Object> issue(AppState state, String accountId, String orderId, long amount,
                                      String challengeType, String requestId) {
        Session session = new Session();
        session.token = UUID.randomUUID().toString().replace("-", "");
        session.sessionId = UUID.randomUUID().toString().replace("-", "");
        session.challengeType = challengeType;
        session.accountId = accountId;
        session.orderId = orderId;
        session.amount = amount;
        session.expireAt = System.currentTimeMillis() + SESSION_TTL_MS;
        state.sessions.put(session.token, session);
        if (requestId != null) {
            state.sessionByRequest.put(requestId, session.token);
        }
        return passResult(session);
    }

    private static Map<String, Object> passResult(Session session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pass", true);
        map.put("challengeType", session.challengeType);
        map.put("verifyToken", session.token);
        map.put("sessionId", session.sessionId);
        map.put("expireAt", session.expireAt);
        map.put("reason", null);
        map.put("freeLimit", FREE_LIMIT);
        map.put("remainAttempts", MAX_FAIL);
        map.put("lockedUntil", null);
        return map;
    }

    private static Map<String, Object> fail(String challengeType, String reason, Integer remain, Long lockedUntil) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pass", false);
        map.put("challengeType", challengeType);
        map.put("verifyToken", null);
        map.put("sessionId", null);
        map.put("expireAt", null);
        map.put("reason", reason);
        map.put("freeLimit", FREE_LIMIT);
        map.put("remainAttempts", remain);
        map.put("lockedUntil", lockedUntil);
        return map;
    }

    /** 验证前置闸门：小额免密 / 一次性令牌（绑定 order+amount+payer）/ 内联口令；失败 403 不受理。 */
    private void verifyGate(String appId, AppState state, String payer, String orderId, long amount,
                            String verifyToken, String payPassword) {
        if (amount <= FREE_LIMIT) {
            return;
        }
        if (verifyToken != null) {
            Session session = state.sessions.get(verifyToken);
            long now = System.currentTimeMillis();
            if (session == null || session.used || session.expireAt <= now
                    || !session.accountId.equals(payer)
                    || (session.orderId != null && !session.orderId.equals(orderId))
                    || session.amount != amount) {
                throw new BizException("A0403", "支付验证无效或已过期");
            }
            session.used = true;
            return;
        }
        if (payPassword != null) {
            Credential credential = state.credentials.get(payer);
            if (credential == null) {
                throw new BizException("A0403", "请先设置支付密码");
            }
            if (credential.lockedUntil != null && credential.lockedUntil > System.currentTimeMillis()) {
                throw new BizException("A0403", "支付密码已锁定，请稍后再试");
            }
            if (!hash(payPassword, credential.salt).equals(credential.hash)) {
                throw new BizException("A0403", "支付口令错误");
            }
            return;
        }
        throw new BizException("A0403", "超出小额免密限额，需支付验证");
    }

    private Map<String, Object> payVerified(String appId, AppState state, Map<String, Object> body) {
        String orderId = required(body, "orderId");
        String requestId = required(body, "requestId");
        synchronized (state) {
            Map<String, Object> prior = state.payResults.get(requestId);
            if (prior != null) {
                return repeated(prior);
            }
            SandboxOrderDTO order = order(appId, orderId);
            String method = normalizeMethod(opt(body, "method"));
            requireBalance(method);
            String accountId = required(body, "accountId");
            openPlatformFacade.sandboxBalance(appId, accountId).orThrow();
            verifyGate(appId, state, accountId, orderId, order.getAmount(),
                    opt(body, "verifyToken"), opt(body, "payPassword"));
            assertOrderNotPaid(state, orderId, requestId);
            return accept(appId, state, orderId, accountId, requestId, order.getAmount(), opt(body, "cardNo"));
        }
    }

    private Boolean setPayPassword(String appId, AppState state, Map<String, Object> body) {
        String accountId = identity(appId, body);
        String password = required(body, "password");
        if (!password.matches("\\d{6}")) {
            throw new BizException("A0400", "支付密码须为 6 位数字");
        }
        synchronized (state) {
            Credential existing = state.credentials.get(accountId);
            String oldPassword = opt(body, "oldPassword");
            if (existing != null && oldPassword != null
                    && !hash(oldPassword, existing.salt).equals(existing.hash)) {
                throw new BizException("A0403", "旧支付密码错误");
            }
            Credential credential = existing == null ? new Credential() : existing;
            credential.salt = UUID.randomUUID().toString().replace("-", "");
            credential.hash = hash(password, credential.salt);
            credential.failCount = 0;
            credential.lockedUntil = null;
            state.credentials.put(accountId, credential);
        }
        log.info("沙箱支付口令已设置 appId={} accountId={}（明文不落库/不回显/不入日志）", appId, accountId);
        return true;
    }

    private Map<String, Object> sendSms(String appId, AppState state, Map<String, Object> body) {
        String accountId = identity(appId, body);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long now = System.currentTimeMillis();
        synchronized (state) {
            Sms sms = new Sms();
            sms.code = code;
            sms.expireAt = now + SMS_TTL_MS;
            state.sms.put(accountId, sms);
        }
        boolean sandbox = boolOpt(body, "sandbox");
        Map<String, Object> map = new LinkedHashMap<>();
        // 形状与生产逐字段一致：sandbox 恒 true（标识沙箱码），是否回显明文由入参 sandbox 决定
        map.put("sandbox", true);
        map.put("smsCode", sandbox ? code : "******");
        if (!sandbox) {
            map.put("smsCodeMasked", true);
        }
        map.put("expireAt", now + SMS_TTL_MS);
        map.put("notice", sandbox ? "沙箱验证码，生产环境经短信网关下发、不在响应中返回"
                : "验证码经短信网关下发至用户手机，不在响应中返回；沙箱联调可传 sandbox=true 取回明文");
        return map;
    }

    // ------------------------------------------------------------------
    // CH-6 签约 / 代扣（沙箱模拟，资金仍走沙箱账本）
    // ------------------------------------------------------------------

    private Map<String, Object> sign(String appId, AppState state, Map<String, Object> body) {
        String action = opt(body, "action") == null ? "SIGN" : opt(body, "action").toUpperCase();
        synchronized (state) {
            if (action.equals("UNSIGN")) {
                String signNo = required(body, "signNo");
                Contract contract = state.contracts.get(signNo);
                if (contract == null) {
                    throw new BizException("A0404", "签约协议不存在: " + signNo);
                }
                if ("SIGNED".equals(contract.status)) {
                    contract.status = "CANCELLED";
                    contract.cancelledAt = System.currentTimeMillis();
                    contract.cancelReason = opt(body, "reason");
                    contract.updatedAt = contract.cancelledAt;
                    log.info("沙箱解约 appId={} signNo={}", appId, signNo);
                }
                return contractMap(contract, false);
            }
            if (!action.equals("SIGN")) {
                throw new BizException("A0400", "action 须为 SIGN/UNSIGN");
            }
            String requestId = required(body, "requestId");
            String priorSignNo = state.contractByRequest.get(requestId);
            Contract byRequest = priorSignNo == null ? null : state.contracts.get(priorSignNo);
            if (byRequest != null) {
                return contractMap(byRequest, true);
            }
            String accountId = required(body, "accountId");
            String merchantId = required(body, "merchantId");
            String cardNo = required(body, "cardNo");
            if (!cardNo.matches("\\d{12,19}") || !luhn(cardNo)) {
                throw new BizException("A0400", "卡号非法（须 12-19 位数字且 Luhn 校验通过）");
            }
            openPlatformFacade.sandboxBalance(appId, accountId).orThrow();
            String channelCode = opt(body, "channelCode") == null ? "MOCK_BANK" : opt(body, "channelCode");
            String triple = accountId + ":" + merchantId + ":" + channelCode;
            String existingSignNo = state.contractByTriple.get(triple);
            Contract existing = existingSignNo == null ? null : state.contracts.get(existingSignNo);
            if (existing != null && "SIGNED".equals(existing.status)) {
                return contractMap(existing, true);
            }
            long now = System.currentTimeMillis();
            Contract contract = new Contract();
            contract.signNo = "SBXSIGN" + SEQ.incrementAndGet();
            contract.accountId = accountId;
            contract.merchantId = merchantId;
            contract.channelCode = channelCode;
            contract.channelSignNo = "SBXCH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            contract.cardNoMask = "**** **** **** " + cardNo.substring(cardNo.length() - 4);
            contract.cardNoHash = sha256(cardNo);
            contract.status = "SIGNED";
            contract.singleLimit = longOpt(body, "singleLimit") == null ? DEFAULT_SINGLE_LIMIT : longOpt(body, "singleLimit");
            contract.periodLimit = longOpt(body, "periodLimit") == null ? DEFAULT_PERIOD_LIMIT : longOpt(body, "periodLimit");
            contract.periodType = opt(body, "periodType") == null ? "DAY" : opt(body, "periodType").toUpperCase();
            contract.effectiveAt = longOpt(body, "effectiveAt") == null ? now : longOpt(body, "effectiveAt");
            contract.expiredAt = longOpt(body, "expiredAt") == null ? now + DEFAULT_VALID_MS : longOpt(body, "expiredAt");
            contract.deductDay = intOpt(body, "deductDay");
            contract.maxRetry = intOpt(body, "maxRetry") == null ? 3 : intOpt(body, "maxRetry");
            contract.retryIntervalMs = longOpt(body, "retryIntervalMs") == null ? 60_000L : longOpt(body, "retryIntervalMs");
            contract.callbackUrl = opt(body, "callbackUrl");
            contract.appId = appId;
            contract.requestId = requestId;
            contract.authorizedScope = "{\"scopeNote\":\"" + (opt(body, "scopeNote") == null ? "" : opt(body, "scopeNote"))
                    + "\",\"sandbox\":true}";
            contract.signedAt = now;
            contract.createdAt = now;
            contract.updatedAt = now;
            state.contracts.put(contract.signNo, contract);
            state.contractByRequest.put(requestId, contract.signNo);
            state.contractByTriple.put(triple, contract.signNo);
            log.info("沙箱签约 appId={} signNo={} accountId={} merchantId={}（卡号只落掩码+哈希）",
                    appId, contract.signNo, accountId, merchantId);
            return contractMap(contract, false);
        }
    }

    private Map<String, Object> agentPay(String appId, AppState state, Map<String, Object> body) {
        String signNo = required(body, "signNo");
        String requestId = required(body, "requestId");
        long amount = requiredLong(body, "amount");
        if (amount <= 0 || amount > 1_000_000_000_000L) {
            throw new BizException("A0400", "amount 必须为正整数分且不超过 1e12");
        }
        synchronized (state) {
            String priorDeductNo = state.deductByRequest.get(requestId);
            Deduct prior = priorDeductNo == null ? null : state.deducts.get(priorDeductNo);
            if (prior != null) {
                return deductMap(prior, true);
            }
            Contract contract = state.contracts.get(signNo);
            if (contract == null) {
                throw new BizException("A0404", "签约协议不存在: " + signNo);
            }
            long now = System.currentTimeMillis();
            // ---- 以下全部为受理前拒绝：不建沙箱订单/资金单（不落悬挂单）----
            if (!"SIGNED".equals(contract.status)) {
                throw new BizException("A0409", "协议不可代扣（状态 " + contract.status + "）: " + signNo);
            }
            if (now < contract.effectiveAt || now > contract.expiredAt) {
                throw new BizException("B0001", now < contract.effectiveAt ? "协议尚未生效: " + signNo : "协议已过期，不可代扣: " + signNo);
            }
            if (contract.deductDay != null && LocalDate.now().getDayOfMonth() != contract.deductDay) {
                throw new BizException("B0001", "今日非约定扣款日（deductDay=" + contract.deductDay + "）");
            }
            String merchantId = opt(body, "merchantId");
            if (merchantId != null && !merchantId.equals(contract.merchantId)) {
                throw new BizException("A0403", "发起商户与协议授权商户不一致");
            }
            if (amount > contract.singleLimit) {
                throw new BizException("B0001", "代扣金额超单笔授权限额: amount=" + amount + " singleLimit=" + contract.singleLimit);
            }
            String periodKey = periodKey(contract.periodType);
            long used = state.deducts.values().stream()
                    .filter(d -> d.signNo.equals(signNo) && "SUCCESS".equals(d.status)
                            && periodKey.equals(d.periodKey))
                    .mapToLong(d -> d.amount).sum();
            if (used + amount > contract.periodLimit) {
                throw new BizException("B0001", "代扣金额超周期累计授权限额: used=" + used + " amount=" + amount + " periodLimit=" + contract.periodLimit);
            }
            long balance = openPlatformFacade.sandboxBalance(appId, contract.accountId).orThrow();
            if (balance < amount) {
                throw new BizException("B0001", "沙箱账户余额不足: " + contract.accountId);
            }
            // ---- 授权全部通过后才建单扣款（沙箱账本，fee=0、同步 SUCCESS）----
            Deduct deduct = new Deduct();
            deduct.deductNo = "SBXD" + SEQ.incrementAndGet();
            deduct.signNo = signNo;
            deduct.accountId = contract.accountId;
            deduct.merchantId = contract.merchantId;
            deduct.requestId = requestId;
            deduct.attemptRequestId = deduct.deductNo + ":A1";
            deduct.amount = amount;
            deduct.periodKey = periodKey;
            deduct.channelCode = contract.channelCode;
            deduct.maxRetry = contract.maxRetry;
            deduct.attempts = 1;
            deduct.createdAt = now;
            String subject = opt(body, "subject") == null ? "代扣-" + signNo : opt(body, "subject");
            SandboxOrderDTO order = openPlatformFacade.sandboxCreateOrder(appId, subject, amount,
                    deduct.deductNo).orThrow();
            SandboxTxnDTO txn = openPlatformFacade.sandboxPay(appId, order.getId(), contract.accountId,
                    "BALANCE", deduct.attemptRequestId).orThrow();
            deduct.orderId = order.getId();
            deduct.paymentId = txn.getId();
            deduct.status = "SUCCESS";
            deduct.finishedAt = System.currentTimeMillis();
            deduct.updatedAt = deduct.finishedAt;
            state.deducts.put(deduct.deductNo, deduct);
            state.deductByRequest.put(requestId, deduct.deductNo);
            log.info("沙箱代扣 appId={} signNo={} deductNo={} amount={} paymentId={}",
                    appId, signNo, deduct.deductNo, amount, txn.getId());
            return deductMap(deduct, false);
        }
    }

    private static Map<String, Object> contractMap(Contract contract, boolean repeated) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("signNo", contract.signNo);
        map.put("accountId", contract.accountId);
        map.put("merchantId", contract.merchantId);
        map.put("channelCode", contract.channelCode);
        map.put("channelSignNo", contract.channelSignNo);
        map.put("cardNoMask", contract.cardNoMask);
        map.put("cardNoHash", contract.cardNoHash);
        map.put("status", contract.status);
        map.put("singleLimit", contract.singleLimit);
        map.put("periodLimit", contract.periodLimit);
        map.put("periodType", contract.periodType);
        map.put("effectiveAt", contract.effectiveAt);
        map.put("expiredAt", contract.expiredAt);
        map.put("deductDay", contract.deductDay);
        map.put("maxRetry", contract.maxRetry);
        map.put("retryIntervalMs", contract.retryIntervalMs);
        map.put("callbackUrl", contract.callbackUrl);
        map.put("appId", contract.appId);
        map.put("requestId", contract.requestId);
        map.put("authorizedScope", contract.authorizedScope);
        map.put("signedAt", contract.signedAt);
        map.put("cancelledAt", contract.cancelledAt);
        map.put("cancelReason", contract.cancelReason);
        map.put("createdAt", contract.createdAt);
        map.put("updatedAt", contract.updatedAt);
        map.put("repeated", repeated);
        return map;
    }

    private static Map<String, Object> deductMap(Deduct deduct, boolean repeated) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deductNo", deduct.deductNo);
        map.put("signNo", deduct.signNo);
        map.put("accountId", deduct.accountId);
        map.put("merchantId", deduct.merchantId);
        map.put("requestId", deduct.requestId);
        map.put("attemptRequestId", deduct.attemptRequestId);
        map.put("orderId", deduct.orderId);
        map.put("paymentId", deduct.paymentId);
        map.put("amount", deduct.amount);
        map.put("status", deduct.status);
        map.put("attempts", deduct.attempts);
        map.put("maxRetry", deduct.maxRetry);
        map.put("nextRetryAt", null);
        map.put("failReason", null);
        map.put("periodKey", deduct.periodKey);
        map.put("channelCode", deduct.channelCode);
        map.put("createdAt", deduct.createdAt);
        map.put("updatedAt", deduct.updatedAt);
        map.put("finishedAt", deduct.finishedAt);
        map.put("repeated", repeated);
        return map;
    }

    // ------------------------------------------------------------------
    // 内部：受理 / 身份 / 工具
    // ------------------------------------------------------------------

    /** 受理（沙箱 BALANCE 同步 SUCCESS，fee=0）；卡号只保留后四位（E-1）。 */
    private Map<String, Object> accept(String appId, AppState state, String orderId, String payer,
                                       String requestId, long amount, String cardNo) {
        SandboxTxnDTO txn = openPlatformFacade.sandboxPay(appId, orderId, payer, "BALANCE", requestId).orThrow();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", txn.getId());
        payment.put("orderId", txn.getOrderId());
        payment.put("bizType", txn.getBizType());
        payment.put("method", txn.getMethod());
        payment.put("payerAccountId", txn.getPayerAccountId());
        payment.put("merchantAccountId", txn.getToAccountId());
        payment.put("amount", txn.getAmount());
        payment.put("fee", txn.getFee());
        payment.put("status", txn.getStatus());
        payment.put("refundedAmount", 0L);
        payment.put("requestId", txn.getRequestId());
        payment.put("cardNo", maskCard(cardNo));
        payment.put("createdAt", txn.getCreatedAt());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("repeated", Boolean.TRUE.equals(txn.getRepeated()));
        state.payResults.put(requestId, result);
        state.orderRequestIds.put(orderId, requestId);
        return result;
    }

    private static Map<String, Object> repeated(Map<String, Object> prior) {
        Map<String, Object> copy = new LinkedHashMap<>(prior);
        copy.put("repeated", true);
        return copy;
    }

    private static void assertOrderNotPaid(AppState state, String orderId, String requestId) {
        String prior = state.orderRequestIds.get(orderId);
        if (prior != null && !prior.equals(requestId)) {
            throw new BizException("A0409", "订单已支付（重复受理）");
        }
    }

    private SandboxOrderDTO order(String appId, String orderId) {
        return openPlatformFacade.sandboxGetOrder(appId, orderId).orThrow();
    }

    private static Map<String, Object> orderMap(SandboxOrderDTO order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("merchantId", order.getMerchantId());
        map.put("merchantName", "沙箱商户-" + order.getMerchantId());
        map.put("outTradeNo", order.getOutTradeNo());
        map.put("subject", order.getSubject());
        map.put("amount", order.getAmount());
        map.put("status", order.getStatus());
        map.put("paymentId", order.getPaymentId());
        map.put("createdAt", order.getCreatedAt());
        return map;
    }

    /** accountId 或 memberId → 沙箱 accountId（口令/短信/会话按账户隔离）。 */
    private String identity(String appId, Map<String, Object> body) {
        String accountId = opt(body, "accountId");
        if (accountId != null) {
            openPlatformFacade.sandboxBalance(appId, accountId).orThrow();
            return accountId;
        }
        String memberId = opt(body, "memberId");
        if (memberId == null) {
            throw new BizException("A0400", "accountId/memberId 至少一项必填");
        }
        return openPlatformFacade.sandboxListAccounts(appId).orThrow().stream()
                .filter(a -> memberId.equals(a.getMemberId())).map(SandboxAccountDTO::getAccountId).findFirst()
                .orElseThrow(() -> new BizException("A0404", "沙箱会员不存在: " + memberId));
    }

    private static void requireBalance(String method) {
        if (method != null && !"BALANCE".equals(method)) {
            throw new BizException("A0400", "沙箱仅支持 BALANCE 支付方式（无渠道调用）");
        }
    }

    private static String normalizeMethod(String method) {
        if (method == null) {
            return "BALANCE";
        }
        String value = method.trim().toUpperCase();
        return "BANKCARD".equals(value) ? "CARD" : value;
    }

    private static String periodKey(String periodType) {
        LocalDate today = LocalDate.now();
        if ("MONTH".equals(periodType)) {
            return today.format(DateTimeFormatter.ofPattern("yyyyMM"));
        }
        if ("NONE".equals(periodType)) {
            return "ALL";
        }
        return today.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String uniqueNumericCode(AppState state) {
        while (true) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < 18; i++) {
                digits.append(RANDOM.nextInt(10));
            }
            String value = digits.toString();
            if (!state.codes.containsKey(value)) {
                return value;
            }
        }
    }

    private static String group(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            return null;
        }
        String value = cardNo.trim();
        return value.length() <= 4 ? "****" : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static boolean luhn(String digits) {
        int total = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            total += d;
            alt = !alt;
        }
        return total % 10 == 0;
    }

    private static String hash(String password, String salt) {
        return sha256(salt + ":" + password);
    }

    private static String sha256(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value.toString().trim();
    }

    private static String opt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static Long longOpt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new BizException("A0400", key + " 无效（须为整数「分」）");
        }
    }

    private static long requiredLong(Map<String, Object> body, String key) {
        Long value = longOpt(body, key);
        if (value == null) {
            throw new BizException("A0400", "缺少参数 " + key);
        }
        return value;
    }

    private static Integer intOpt(Map<String, Object> body, String key) {
        Long value = longOpt(body, key);
        return value == null ? null : value.intValue();
    }

    private static boolean boolOpt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    // ------------------------------------------------------------------
    // 按 appId 隔离的沙箱态（进程内，联调用；重启即清，不影响生产账本）
    // ------------------------------------------------------------------

    private static final class AppState {
        final Map<String, Code> codes = new ConcurrentHashMap<>();
        final Map<String, String> codeByRequest = new ConcurrentHashMap<>();
        final Map<String, Credential> credentials = new ConcurrentHashMap<>();
        final Map<String, Sms> sms = new ConcurrentHashMap<>();
        final Map<String, Session> sessions = new ConcurrentHashMap<>();
        final Map<String, String> sessionByRequest = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> payResults = new ConcurrentHashMap<>();
        final Map<String, String> orderRequestIds = new ConcurrentHashMap<>();
        final Map<String, Contract> contracts = new ConcurrentHashMap<>();
        final Map<String, String> contractByRequest = new ConcurrentHashMap<>();
        final Map<String, String> contractByTriple = new ConcurrentHashMap<>();
        final Map<String, Deduct> deducts = new ConcurrentHashMap<>();
        final Map<String, String> deductByRequest = new ConcurrentHashMap<>();
    }

    private static final class Code {
        String code;
        String type;
        String accountId;
        String orderId;
        Long amount;
        String status;
        long expireAt;
        long createdAt;
        Long consumedAt;
        String consumedRequestId;
        String qrContent;
        String displayCode;
        String merchantName;
        String subject;
        String requestId;
    }

    private static final class Credential {
        String salt;
        String hash;
        int failCount;
        Long lockedUntil;
    }

    private static final class Sms {
        String code;
        long expireAt;
        boolean used;
    }

    private static final class Session {
        String token;
        String sessionId;
        String accountId;
        String orderId;
        long amount;
        long expireAt;
        boolean used;
        String challengeType;
    }

    private static final class Contract {
        String signNo;
        String accountId;
        String merchantId;
        String channelCode;
        String channelSignNo;
        String cardNoMask;
        String cardNoHash;
        String status;
        long singleLimit;
        long periodLimit;
        String periodType;
        long effectiveAt;
        long expiredAt;
        Integer deductDay;
        Integer maxRetry;
        Long retryIntervalMs;
        String callbackUrl;
        String appId;
        String requestId;
        String authorizedScope;
        Long signedAt;
        Long cancelledAt;
        String cancelReason;
        Long createdAt;
        Long updatedAt;
    }

    private static final class Deduct {
        String deductNo;
        String signNo;
        String accountId;
        String merchantId;
        String requestId;
        String attemptRequestId;
        String orderId;
        String paymentId;
        long amount;
        String status;
        Integer attempts;
        Integer maxRetry;
        String periodKey;
        String channelCode;
        Long createdAt;
        Long updatedAt;
        Long finishedAt;
    }
}