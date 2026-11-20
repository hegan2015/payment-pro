# 收银台开放 OpenAPI 对接契约（ride-pro 打车系统 + 未来其他端）

> **定位**：payment-pro **不自带 C 端 H5**（原 F 泳道原型已移至 `ui-design/h5-prototype/`，服务路径 `/h5/**` 已摘除，访问返回 404）。
> C 端收银台页面由 **业务方自建**（首个接入方 ride-pro），通过本文 9 个 `cashier.*` 商户方法调用 payment-pro 收银台能力。
> **C-5 口径 = 多端对接能力**（开放 OpenAPI + 双 SDK + 契约），各端适配见 [`cashier-multiclient.md`](cashier-multiclient.md)。
>
> **实现**：`payment-gateway/src/main/java/com/qoder/payment/gateway/web/OpenApiCashierController.java`（additive 新文件）
> + `OpenApiSignController.java`（签约代扣 2 方法，见 §6）。
> 验签复用 `OpenApiController.verifyFor(body, endpoint)` —— 与既有 11 个商户方法**同一套** HMAC/RSA2 链路。
> **回归脚本**：`verify-cashier.py` A1/A2 段（H5 摘除 + 9 方法验签/错签/时间窗/nonce/幂等/脱敏/码过期/验证四型/衔接）
> + `verify-multiclient.py`（契约一致性/双 SDK/沙箱隔离/签约实签/scope·版本·沙箱·限流登记/appM1 legacy 不误拦/md5 冻结）。

---

## 1. 通用调用约定

| 项 | 值 |
|---|---|
| 端点 | `POST {BASE}/openapi/<method>`，如 `POST /openapi/cashier.methods` |
| BASE | 本地 docker 全栈：`http://127.0.0.1:8080`（HTTPS 自签 `https://127.0.0.1:8443`） |
| Content-Type | `application/json` |
| 鉴权 | **HMAC-SHA256 签名，免 Bearer**（`/openapi/**` 走独立链，不走控制台 RBAC） |
| 请求体 | **扁平标量参数**（字符串/数字/布尔）+ 签名三要素；不支持嵌套对象/数组（canonical 按 `String.valueOf` 拼接） |
| 成功响应 | **裸数据**（与控制台面 `/api/cashier/*` 结构一致，不套 envelope） |
| 失败响应 | `{"error":"文案","code":"A0400","traceId":"..."}` |
| 金额 | 全程 **long 型「分」**，禁止浮点 |
| 时间戳 | **毫秒**（`System.currentTimeMillis()`），有效窗 **±300s** |

### 1.1 签名三要素（+ 可选 nonce）

| 参数 | 必填 | 说明 |
|---|---|---|
| `appId` | 是 | 商户 appId（`POST /api/members` 创建商户时一次性返回 `appId`/`appSecret`；之后 list/get 已脱敏，务必持久化） |
| `timestamp` | 是 | 毫秒时间戳，±300s 外拒绝 `A0400` |
| `sign` | 是 | HMAC-SHA256 十六进制**小写**串（默认）；`sign_type=RSA2` 时为 Base64 RSA 签名 |
| `nonce` | 否 | 随机串 ≤64 字符，**同 appId 300s 内不可重复**；重复 → `A0409`。**不传则完全跳过**（ride 既有调用兼容红线） |
| `sign_type` | 否 | `HMAC`（默认，可省略）/ `RSA2`（需先上传商户证书公钥） |

### 1.2 canonical 串构造（与既有 11 方法逐字节一致）

1. 取请求体全部参数，**剔除 `sign`**，**跳过值为 `null` 的键**（空串 `""` 参与签名，不要传空串）；
2. 按 key 的**自然字典序升序**排序（Java `TreeMap`；ASCII 键等价于 Python `sorted()`）；
3. 以 `k=v` 拼接，分隔符 `&`，**不做 URL encode**、不加末尾 `&`；
4. `sign = HMAC_SHA256(appSecret, canonical)` → 小写 hex。

示例（`cashier.methods`）：

```
appId=appM1&accountId=ACC1001&orderId=ORD2001&timestamp=1795141749123
```

> `nonce`/`sign_type`/`sandbox` 等可选参数**一旦传入就必须参与签名**（它们在 body 里）。

### 1.3 治理与拒绝口径（全部发生在业务受理之前，不落悬挂支付单）

| 场景 | HTTP | code | 说明 |
|---|---|---|---|
| 签名不匹配 | 400 | `A0400` | `签名验证失败`（ACTIVE 密钥 + 宽限期 RETIRED 密钥都试过） |
| timestamp 缺失/非数字 | 400 | `A0400` | `timestamp 无效` |
| timestamp 超 ±300s | 400 | `A0400` | `timestamp 超出 5 分钟有效期` |
| 缺 `appId` | 400 | `A0400` | `缺少参数 appId` |
| appId 不存在 | 404 | `A0404` | `appId 不存在: xxx` |
| nonce 重复 | 409 | `A0409` | `重复请求：nonce 已使用` |
| nonce >64 字符 | 400 | `A0400` | `nonce 超长（最多 64 字符）` |
| appId 热点 QPS 超限（I-7 Sentinel） | 429 | `A0429` | 资源 `openapi:<method>`，`paramIdx=0`=appId；**拒绝在受理前**，不启动 TCC |
| 下游熔断（I-7） | 503 | `B0003` | 服务熔断降级 |
| RSA2 未上传证书 | 400 | `A0400` | `RSA2 证书缺失，请先上传商户证书` |

> **限流阈值（已落地）**：9 个 `openapi:cashier.*` + 2 个 `openapi:channel.*` 的 `paramFlowRules` 已追加到
> `~/.config/payment-pro/sentinel-rules.json`（仓库同步 `config/sentinel-rules.json.example`），阈值见 §7.3。
> 拒绝在验签通过后、业务受理前 → **不落悬挂支付单**；Sentinel 自身故障一律 fail-open，不阻断支付。
> **未注册 appId（ride `appM1`）走 legacy**：新 scope/配额/沙箱闸门全部空转，顺序流量（<5 QPS）不受新规则影响。

### 1.4 业务错误码（沿用既有体系）

| code | HTTP | 典型含义 |
|---|---|---|
| `A0400` | 400 | 参数缺失/非法（`缺少参数 xxx`、`type 须为 ...`、`requestId 必填（幂等键）`） |
| `A0403` | 403 | 支付验证无效/过期/已使用、超小额免密限额未验证、旧支付口令错误 |
| `A0404` | 404 | 付款码不存在、订单不存在、账户不存在 |
| `A0409` | 409 | 付款码已过期/已核销（防重放）、订单级重复支付、nonce 重放 |
| `A0429` | 429 | 限流 |
| `B0001` | 400 | 业务失败（风控拦截/余额不足等，以 `error` 文案为准；风控滑动窗口提示「频繁」时**等窗口重置重试，不要放宽阈值**） |
| `B0002` | 500 | 系统内部错误 |
| `B0003` | 503 | 熔断降级 |
| `C0001` | 502 | 下游服务异常 |

### 1.5 脱敏（E-1）

- 响应**永不含** `appSecret` / `certPublicKey`；
- **不回显支付口令**（`password`/`payPassword` 只入不出，服务端哈希加盐存储）；
- **银行卡号只保留后四位**：`6222020202020202` → `**********0202`（`cashier.scanPay` / `cashier.payVerified` 的 `payment.cardNo`；
  上游支付域 DTO 已掩「前 6 后 4」，开放面进一步把 BIN 也抹除，**除后四位外全部为 `*`**）；
- **短信验证码默认掩码**：`smsCode="******"` + `smsCodeMasked=true`；仅显式传 `sandbox=true`（联调）时返回沙箱 6 位明文，结构与控制台面一致。**生产链路不得依赖响应取码**。

### 1.6 幂等

- `requestId` 是**全局幂等键**，语义与既有 `trade.pay` 的 `outTradeNo`、`fund.*` 的 `bizNo` 一致：
  同 `requestId` 重复受理 → 返回**同一支付单**且 `repeated=true`，**不重复扣款**；
- `cashier.verify` 传 `requestId` 时，同键重复验证返回**同一会话令牌**；
- `cashier.code.create` 传 `requestId` 时，同键重复生成返回同一有效码；
- 付款码**一次性核销**（CAS `ACTIVE→CONSUMED`）：同码异 `requestId` → `A0409`；并发核销恰一成功。

---

## 2. 9 个方法契约

> 下表「入参」省略通用签名三要素（`appId`/`timestamp`/`sign`，可选 `nonce`/`sign_type`）。
> 返回均为**裸数据**。

### 2.1 `cashier.methods` — 支付方式渲染（C-1）
对应控制台面 `GET /api/cashier/methods`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `orderId` | 是 | 交易单 id（`trade.create` 返回） |
| `accountId` | 否 | 付款账户；传入=账户级可用性判定，缺省=聚合渲染（全部 ACTIVE USER 账户） |

**返回** `CashierDTO`：
```json
{
  "order": {"id":"ORD...","merchantId":"M...","merchantName":"...","subject":"...","amount":3000,"status":"CREATED","paymentId":null},
  "methods": [
    {"method":"BALANCE","name":"余额","hint":"...","accounts":[{"id":"ACC...","memberId":"M...","memberName":"...","type":"USER","status":"ACTIVE","balance":100000}],
     "available":true,"unavailableReason":null,"limit":500000,"category":"REAL","maintenance":false,"enabled":true,"icon":"wallet"}
  ],
  "merchantName":"收款商户名","discount":0,"actualAmount":3000,"freeVerifyLimit":1000,"currency":"CNY"
}
```
- `method` 枚举：`BALANCE`/`CARD`/`PAY_CODE`/`SCAN`（REAL，可受理）+ `QUICK_PAY`/`NET_BANK`/`CREDIT`/`COUPON`/`COMBO`（`category=RESERVED`，`available=false` + `unavailableReason`）。
- 不可用原因实时聚合：账户冻结、余额不足、未签约、维护窗口等。
- `freeVerifyLimit`：小额免密限额（分），`amount ≤` 此值可跳过支付验证。
- **错误**：`orderId` 缺失 `A0400`；订单不存在 `A0404`；账户不存在 `A0404`。

### 2.2 `cashier.code.create` — 生成付款码/收款码（C-2，沙箱）
对应 `POST /api/cashier/code`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `type` | 是 | `USER_PRESENTED`（被扫：用户出示 18 位数字码，商户扫）/ `MERCHANT_PRESENTED`（主扫：商户出二维码，用户扫） |
| `accountId` | 条件 | 被扫=付款账户（必填）；主扫=收款商户账户 |
| `orderId` | 条件 | 与 `amount` 至少一项；有 `orderId` 时金额以订单为准 |
| `amount` | 条件 | 分；无 `orderId` 时生成定额码（**定额码不可受理支付**） |
| `requestId` | 否 | 幂等键，同键返回同一有效码 |

**返回** `PayCodeDTO`：
```json
{"code":"123456789012345678","type":"USER_PRESENTED","accountId":"ACC...","orderId":"ORD...","amount":100,
 "status":"ACTIVE","expireAt":1795141809123,"createdAt":1795141749123,"consumedAt":null,
 "qrContent":null,"displayCode":"1234 5678 9012 3456 78","merchantName":"...","subject":"...","ttlSeconds":60}
```
- 主扫（`MERCHANT_PRESENTED`，商户出示、用户扫）：`code` 以 `MP` 开头；**`qrContent` 是扫码 URL 而非码值本身**，形如 `https://cashier.payment-pro.example/scan?code=<token>&amount=<amount>`（沙箱 example 域，明确非真实渠道）。前端据此绘 QR；**用户扫码后需从 URL 的 `code=` 查询参数归一取出 token 再调 `cashier.code.parse` / `cashier.scanPay`**（ride-pro 已实现该归一，见其 `static/cashier.js`）。直接拿整串 URL 当 code 调用会 404。
- 被扫（`USER_PRESENTED`，用户出示、商户扫）：`qrContent` **等于码值本身**（纯数字串，同一串既可绘条码也可绘 QR），`displayCode` 为 4 位分组展示串（如 `1515 9175 3762 2717 73`）。
- 有效期默认 **60s**（可配），到期状态自然流转 `EXPIRED`。
- **错误**：`type` 非法/缺失 `A0400`；`orderId/amount` 都缺 `A0400`；被扫缺 `accountId` `A0400`。

### 2.3 `cashier.code.parse` — 解析码（只读，不核销）
对应 `POST /api/cashier/code/parse`。入参 `code`（必填）。返回 `PayCodeDTO`（同上）。
**错误**：缺 `code` `A0400`；码不存在 `A0404`。

### 2.4 `cashier.code.query` — 查询码状态（轮询）
对应 `GET /api/cashier/code/{code}`。入参 `code`（必填）。返回 `PayCodeDTO`，`status ∈ ACTIVE/CONSUMED/EXPIRED`，`ttlSeconds` 供倒计时。
**错误**：缺 `code` `A0400`；码不存在 `A0404`。

### 2.5 `cashier.scanPay` — 扫码受理（核销 + 验证 + 幂等支付）
对应 `POST /api/cashier/scan-pay`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `code` | 是 | 待核销码值 |
| `requestId` | 是 | 全局幂等键 |
| `payerAccountId` | 条件 | **主扫必填**；被扫可空（取码绑定账户） |
| `method` | 否 | 默认 `BALANCE`；`BANKCARD` 自动归一为 `CARD` |
| `verifyToken` | 否 | `cashier.verify` 签发的一次性令牌 |
| `payPassword` | 否 | 内联口令验证（与 `verifyToken` 二选一） |
| `cardNo` | 否 | `method=CARD` 时卡号（响应脱敏） |
| `callbackUrl` | 否 | 支付结果通知地址，**支持 http** |

**返回** `PayResultDTO`：`{"payment":{PaymentDTO},"repeated":false}`；`payment.cardNo` 已脱敏。
`BALANCE` 走**同步受理 → PROCESSING → SUCCESS**（与 `trade.pay` 逐字节一致，无 WAITING/异步回执）；手续费口径不变（`feeBps=60` → `SYS_FEE`）。
**错误**：缺 `code`/`requestId` `A0400`；码不存在 `A0404`；码已过期 `A0409`；码未绑定订单 `A0400`；主扫缺 `payerAccountId` `A0400`；已核销防重放 `A0409`；验证无效 `A0403`；超免密未验证 `A0403`。

### 2.6 `cashier.verify` — 支付验证（C-3，四型）
对应 `POST /api/cashier/verify`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `accountId` / `memberId` | 是（二选一） | 付款账户或会员 |
| `orderId` | 否 | 令牌绑定订单（受理时校验跨单复用） |
| `amount` | 否 | 分；用于小额免密判定与会话绑定 |
| `verifyType` | 否 | `PASSWORD`（默认）/ `SMS` / `BIO` / `SMALL_AMOUNT_FREE` |
| `payPassword` | 条件 | `PASSWORD` 时必填（6 位数字） |
| `smsCode` | 条件 | `SMS` 时必填（先调 `cashier.sms.send`） |
| `bioToken` | 条件 | `BIO` 时必填（端侧采集，沙箱模拟平台校验） |
| `requestId` | 否 | 幂等键，同键返回同一会话 |

**返回** `VerifyResultDTO`：
```json
{"pass":true,"challengeType":"PASSWORD","verifyToken":"...","sessionId":"...","expireAt":1795142049123,
 "reason":null,"freeLimit":1000,"remainAttempts":4,"lockedUntil":null}
```
- `pass=false` 时 `challengeType` 可为 `NEED_SET_PASSWORD`（未设口令，引导 `cashier.payPassword.set`）/ `LOCKED`（连续错口令锁定，附 `lockedUntil`）；`reason` 为明确文案，`remainAttempts` 为剩余可失败次数。
- **验证失败不受理支付、不落单、不扣款**。
- `verifyToken` **一次性**，且绑定 `orderId + amount + payer`；复用/跨单 → 受理侧 `A0403`。
- **错误**：`accountId`/`memberId` 都缺 `A0400`。

### 2.7 `cashier.payVerified` — 验证前置受理（幂等）
对应 `POST /api/cashier/pay-verified`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `orderId` | 是 | 交易单 id |
| `requestId` | 是 | 全局幂等键（验证与受理同键不重复扣款） |
| `method` | 否 | 默认 `BALANCE`；`BANKCARD` → `CARD` |
| `accountId` | 条件 | `BALANCE` 必填 |
| `verifyToken` / `payPassword` | 条件 | 二选一；`amount ≤ freeVerifyLimit` 时可都不传（小额免密） |
| `cardNo` | 否 | `CARD` 卡号（响应脱敏） |
| `callbackUrl` | 否 | 支持 http |

**返回** `PayResultDTO`（同 2.5，`cardNo` 脱敏）。
**错误**：缺 `orderId`/`requestId` `A0400`；订单不存在 `A0404`；验证无效/过期/已用 `A0403`；超免密未验证 `A0403`；订单级重复支付（异 `requestId`）`A0409`。

### 2.8 `cashier.payPassword.set` — 设置/重置支付口令（沙箱）
对应 `POST /api/cashier/pay-password`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `accountId` / `memberId` | 是（二选一） | 目标会员 |
| `password` | 是 | **6 位数字** |
| `oldPassword` | 否 | 重置时校验旧口令（首次设置可空） |

**返回**：裸 `true`（boolean）。口令明文**不落库、不回显、不入日志**。
**错误**：非 6 位数字 `A0400`；旧口令错误 `A0403`；身份缺失 `A0400`。

### 2.9 `cashier.sms.send` — 发送短信验证码（沙箱）
对应 `POST /api/cashier/sms/send`。

| 入参 | 必填 | 说明 |
|---|---|---|
| `accountId` / `memberId` | 是（二选一） | 收码会员 |
| `orderId` | 否 | 绑定订单 |
| `sandbox` | 否 | `true` 时返回沙箱明文码（**仅联调**）；缺省/false → 掩码 |

**返回**：
```json
{"sandbox":true,"smsCode":"******","smsCodeMasked":true,"expireAt":1795142049123,
 "notice":"验证码经短信网关下发至用户手机，不在响应中返回；沙箱联调可传 sandbox=true 取回明文"}
```
`sandbox=true` 时 `smsCode` 为 6 位明文、无 `smsCodeMasked` 字段（与控制台面结构一致）。验证码 TTL 默认 300s。
**错误**：身份缺失 `A0400`。

---

## 3. ride-pro 推荐对接序列

> **前置条件（实测踩坑）**：**收款商户必须已开户**（`account.open` 或控制台 `POST /api/accounts`）。
> 商户会员未开户时，`cashier.payVerified` / `cashier.scanPay` 受理返回 **404 `A0404` `会员未开户: {merchantId}`**
> （付款账户余额不足/冻结则在渲染阶段即标 `available=false`）。付款用户同样需先开户并充值。

**A. 余额支付（主路径，与既有 `trade.pay` 语义一致）**
```
trade.create → cashier.methods（渲染可用方式/免密限额）
  ├─ amount ≤ freeVerifyLimit → cashier.payVerified（免验证）
  └─ amount > freeVerifyLimit → cashier.verify（PASSWORD/SMS/BIO）→ cashier.payVerified（携 verifyToken）
→ 轮询 trade.query 或支付单查询至 SUCCESS（BALANCE 同步，通常首次即终态）
```
**B. 被扫（用户出示付款码，司机/商户扫）**
```
用户端：cashier.code.create(type=USER_PRESENTED, accountId, orderId) → 展示 18 位码/条码
商户端：cashier.code.parse（校验金额/订单）→ cashier.scanPay(code, requestId)
用户端：cashier.code.query 轮询 ACTIVE→CONSUMED；到期 EXPIRED 则重新生成
```
**C. 主扫（商户出码，用户扫）**
```
商户端：cashier.code.create(type=MERCHANT_PRESENTED, accountId=商户账户, orderId) → qrContent 绘 QR
用户端：cashier.code.parse → cashier.scanPay(code, requestId, payerAccountId=用户账户)
```
> 首次使用需引导用户 `cashier.payPassword.set`；`cashier.verify` 返回 `NEED_SET_PASSWORD` 即为该场景。
> 口令连续错误会 `LOCKED`（附 `lockedUntil`），端侧应展示锁定倒计时而非无限重试。

---

## 4. 可直接照抄的调用示例

### 4.1 Java（JDK 11+ `HttpClient`，零第三方依赖）

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class CashierOpenApiClient {

    private static final String BASE = "http://127.0.0.1:8080";
    private final String appId;
    private final String appSecret;   // 从密钥仓库/配置中心读取，勿硬编码
    private final HttpClient http = HttpClient.newHttpClient();

    public CashierOpenApiClient(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
    }

    /** canonical：TreeMap 升序 k=v&，剔除 sign、跳过 null，不做 URL encode。 */
    static String canonical(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(params).entrySet()) {
            if (e.getValue() == null || "sign".equals(e.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** 调用任意 cashier.* 方法；params 只放业务参数（扁平标量）。 */
    public String call(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new TreeMap<>(params);
        body.put("appId", appId);
        body.put("timestamp", System.currentTimeMillis());   // 毫秒
        // body.put("nonce", UUID.randomUUID().toString());  // 可选防重放（≤64 字符，300s 内唯一）
        body.put("sign", hmacSha256Hex(appSecret, canonical(body)));

        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                json.append(v);
            } else {
                json.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        json.append('}');

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/openapi/" + method))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(method + " 失败 " + response.statusCode() + ": " + response.body());
        }
        return response.body();   // 裸数据 JSON
    }

    /** 示例：渲染 → 验证 → 受理（金额单位「分」）。 */
    public static void main(String[] args) throws Exception {
        CashierOpenApiClient client = new CashierOpenApiClient(
                System.getenv("PP_APP_ID"), System.getenv("PP_APP_SECRET"));
        String orderId = args[0], accountId = args[1];

        System.out.println(client.call("cashier.methods",
                Map.of("orderId", orderId, "accountId", accountId)));

        String verifyJson = client.call("cashier.verify", Map.of(
                "accountId", accountId, "orderId", orderId, "amount", 3000,
                "verifyType", "PASSWORD", "payPassword", System.getenv("PP_PAY_PASSWORD")));
        String verifyToken = verifyJson.replaceAll(".*\"verifyToken\":\"([^\"]+)\".*", "$1");

        System.out.println(client.call("cashier.payVerified", Map.of(
                "orderId", orderId, "accountId", accountId, "method", "BALANCE",
                "requestId", "ride-pay-" + orderId,     // 全局幂等键：同键不重复扣款
                "verifyToken", verifyToken)));
    }
}
```

### 4.2 curl（bash + openssl 计算签名）

```bash
BASE=http://127.0.0.1:8080
APP_ID="$PP_APP_ID"            # 商户 appId
APP_SECRET="$PP_APP_SECRET"    # 从密钥仓库注入环境变量，勿写进脚本/日志
ORDER_ID=ORD2001
ACCOUNT_ID=ACC1001
TS=$(( $(date +%s) * 1000 ))   # 毫秒

# canonical：key 升序、k=v&、剔除 sign、跳过 null（此例键序 accountId < appId < orderId < timestamp）
CANON="accountId=${ACCOUNT_ID}&appId=${APP_ID}&orderId=${ORDER_ID}&timestamp=${TS}"
SIGN=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | sed 's/^.*= //')

curl -sS -X POST "$BASE/openapi/cashier.methods" \
  -H 'Content-Type: application/json' \
  -d "{\"appId\":\"$APP_ID\",\"timestamp\":$TS,\"orderId\":\"$ORDER_ID\",\"accountId\":\"$ACCOUNT_ID\",\"sign\":\"$SIGN\"}"
```

验证 + 受理（两步，`requestId` 为幂等键）：

```bash
TS=$(( $(date +%s) * 1000 ))
CANON="accountId=${ACCOUNT_ID}&amount=3000&appId=${APP_ID}&orderId=${ORDER_ID}&payPassword=${PAY_PASSWORD}&timestamp=${TS}&verifyType=PASSWORD"
SIGN=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | sed 's/^.*= //')
VERIFY=$(curl -sS -X POST "$BASE/openapi/cashier.verify" -H 'Content-Type: application/json' \
  -d "{\"appId\":\"$APP_ID\",\"timestamp\":$TS,\"accountId\":\"$ACCOUNT_ID\",\"orderId\":\"$ORDER_ID\",\"amount\":3000,\"verifyType\":\"PASSWORD\",\"payPassword\":\"$PAY_PASSWORD\",\"sign\":\"$SIGN\"}")
TOKEN=$(printf '%s' "$VERIFY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["verifyToken"])')

TS=$(( $(date +%s) * 1000 ))
RID="ride-pay-${ORDER_ID}"
CANON="accountId=${ACCOUNT_ID}&appId=${APP_ID}&method=BALANCE&orderId=${ORDER_ID}&requestId=${RID}&timestamp=${TS}&verifyToken=${TOKEN}"
SIGN=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | sed 's/^.*= //')
curl -sS -X POST "$BASE/openapi/cashier.payVerified" -H 'Content-Type: application/json' \
  -d "{\"appId\":\"$APP_ID\",\"timestamp\":$TS,\"orderId\":\"$ORDER_ID\",\"accountId\":\"$ACCOUNT_ID\",\"method\":\"BALANCE\",\"requestId\":\"$RID\",\"verifyToken\":\"$TOKEN\",\"sign\":\"$SIGN\"}"
```

> 调试提示：`400 签名验证失败` 九成是 canonical 不一致——检查①是否漏签某个已传参数（含 `nonce`/`sandbox`）；
> ②是否传了空串（空串参与签名，`null` 不参与）；③`timestamp` 是否用了秒；④键序是否严格升序；⑤是否做了 URL encode（不要做）；
> ⑥**布尔值** canonical 按 Java `String.valueOf` 渲染为小写 `true`/`false`（Python `str(True)` 是 `True`，需显式转小写）。

### 4.3 实测签名样例（docker 全栈实跑，可用于自检 canonical 实现）

`cashier.methods`（appId=`appM1213`，付款账户 `A1152`，订单 `O3023`）：

```
canonical = accountId=A1152&appId=appM1213&orderId=O3023&timestamp=1795144183243
sign      = 454bc8d580b62ca040ac8cc2ffa3696685179dca4e02da27d3bfbfe4cfcc50e8
HTTP 200  → {"order":{...},"methods":[...],"actualAmount":100,"freeVerifyLimit":1000,...}
```

`cashier.payVerified`（appId=`appM1215`，口令/令牌已隐去）：

```
canonical = accountId=A1154&appId=appM1215&method=BALANCE&orderId=O3025&requestId=CSOPDEMO3981d9-pay&timestamp=1795144216512&verifyToken=<token>
HTTP 200  → {"payment":{"id":"P1689","method":"BALANCE","amount":100,"fee":1,"status":"SUCCESS",...},"repeated":false}
```

`cashier.payVerified`（CARD，卡号脱敏实测）：

```
入参 cardNo=6222020202020202, method=BANKCARD
HTTP 200 → payment.method="CARD", payment.cardNo="**********0202"
```

> 9 个方法的完整实跑断言见 `verify-cashier.py` A2 段（199 项全绿，含错签/时间窗/nonce/幂等/脱敏/过期/锁定）。

---

## 5. 兼容红线（payment 侧承诺）

1. 既有 11 个商户方法（`account.open/topup/transfer/withdraw/balance`、`fund.topup/withdraw`、`trade.create/pay/refund/query`）**入参名/返回字段/幂等键/语义逐字节不变**；`account.balance` 仍是裸数字 long。
2. HMAC canonical 算法、毫秒 timestamp、±300s 窗、可选 nonce 口径不变；`/openapi/**` 继续免 Bearer。
3. `BALANCE`/资金单（无渠道分支）**同步受理 → PROCESSING → SUCCESS**，不引入 WAITING/异步回执；手续费口径（`feeBps→SYS_FEE`）不变。
4. `callbackUrl` 通知继续支持 **http**，body 结构不变。
5. 本次只新增 9 个 `cashier.*` 方法 + 摘除 `/h5/**` 静态路径；**未改** `OpenApiController`/`SecurityConfig`/cashier 域业务逻辑/任何 facade 接口。
6. C 端 H5 原型完整保留在 `ui-design/h5-prototype/`（11 个文件，可逆），仅作设计参照，**不再由网关服务**。
7. **后续 additive（C-5 收尾 + CH-6 开放面）**：新增 `channel.sign`/`channel.agent.pay`（§6，独立 `OpenApiSignController`，
   `OpenApiController` 本体 md5 仍为 `58870299aef5f32edc93593ac8b32f09` 未变）；`OpenApiScopes`/`ApiVersionRouter`/`SandboxRouter`
   只做**追加登记**（§7），v1 冻结清单与既有 11 条限流阈值一字未改；**未注册 appId legacy 免费不限流不限配额**。

---

## 6. 签约代扣 2 方法契约（CH-6 开放面，additive）

> 实现：`payment-gateway/.../web/OpenApiSignController.java`（**新文件**，复用 `verifyFor`，不改 `OpenApiController` 本体）。
> 只调 `SignFacade` **既有**方法（`signContract`/`unsignContract`/`agentPay`），**不给任何域 facade 新增方法**、不改 channel 域 sign 业务逻辑。
> 控制台面对应 `POST /api/channel/sign`、`/api/channel/sign/{signNo}/unsign`、`/api/channel/agent-pay`（需 `X-Step-Up-Token`）；
> **开放面以 HMAC 商户身份为凭**，`operator` 恒取验签商户（`openapi:<appId>`），**不接受 body 冒充**。
>
> **信任边界（需主会话复核）**：注册应用须持 `channel:sign` / `channel:agentpay` scope 才可调用；
> `merchantId` 是**被授权代扣方**，域内既有实现不做 `operator ↔ merchantId` 绑定校验（控制台面同口径），
> 即持 scope 的商户可为其他 merchantId 建立授权。若需「只能为自己签约」的强约束，
> 应在开放面追加 `merchantId == 验签商户 id` 校验（**属产品口径决策，本泳道未擅自加闸**）。

### 6.1 `channel.sign` — 签约 / 解约

| 入参 | 必填 | 说明 |
|---|---|---|
| `action` | 否 | `SIGN`（默认）/ `UNSIGN` |
| `requestId` | 是 | 幂等键（`sign_contract.request_id` UNIQUE） |
| `accountId` | SIGN 必填 | 被扣款账户（授权人） |
| `merchantId` | SIGN 必填 | 授权商户（代扣发起方） |
| `cardNo` | SIGN 必填 | 银行卡号明文（12-19 位数字 + **Luhn 校验**；沙箱卡）。**只入参**：落库掩码 + SHA-256 哈希 + AES-GCM 密文 |
| `channelCode` | 否 | 签约渠道，缺省 `MOCK_BANK`（`DynamicConfig channel.route.default-channel`） |
| `singleLimit` | 否 | 单笔代扣限额（分），缺省 `sign.default.single-limit` |
| `periodLimit` | 否 | 周期累计限额（分），缺省 `sign.default.period-limit` |
| `periodType` | 否 | `DAY`（默认）/ `MONTH` / `NONE` |
| `effectiveAt` / `expiredAt` | 否 | epoch 毫秒；缺省 now / now + `sign.default.valid-days` |
| `deductDay` | 否 | 扣款日 1-28；非扣款日新代扣**受理前拒绝** |
| `maxRetry` / `retryIntervalMs` | 否 | 缺省 3 次 / 60000ms（线性退避） |
| `callbackUrl` | 否 | 签约/解约事件回调（**支持 http**，缺省 PULL 模式） |
| `scopeNote` | 否 | 授权范围补充说明（落 `authorized_scope` JSON 留痕） |
| `signNo` | UNSIGN 必填 | 待解约协议号 |
| `reason` | 否 | 解约原因（留痕） |

**返回** `SignContractDTO`（裸数据）：
```json
{"signNo":"S...","accountId":"A...","merchantId":"M...","channelCode":"MOCK_BANK","channelSignNo":"...",
 "cardNoMask":"**** **** **** 0202","cardNoHash":"<sha256 hex 64>","status":"SIGNED",
 "singleLimit":5000,"periodLimit":20000,"periodType":"DAY","effectiveAt":1795152000000,"expiredAt":1826688000000,
 "deductDay":null,"maxRetry":3,"retryIntervalMs":60000,"callbackUrl":null,"appId":"appM...","requestId":"...",
 "authorizedScope":"{...}","signedAt":1795152000000,"cancelledAt":null,"cancelReason":null,
 "createdAt":1795152000000,"updatedAt":1795152000000,"repeated":false}
```
- 状态机 `SIGNED → CANCELLED`（解约）/ `SIGNED → EXPIRED`（到期作业）；终态不可逆，解约/到期后不可代扣。
- **幂等**：同 `requestId` 或同三元组 `(accountId, merchantId, channelCode)` 已有 SIGNED 协议 → 返回既有协议 + `repeated=true`，不重复签约。
- **解约幂等**：已 `CANCELLED` 直接返回当前快照（不重复投递事件）；在途扣款单不被解约打断。
- **脱敏**：响应**绝不含**明文卡号/密文（`cardNoCipher`）/`appSecret`；明文不入日志。
- **错误**：缺 `requestId`/`accountId`/`merchantId`/`cardNo`（SIGN）或缺 `signNo`（UNSIGN）`A0400`；
  Luhn 非法 `A0400`；`action` 非法 `A0400`；协议不存在 `A0404`；账户冻结/卡已解绑/渠道拒绝 → **受理前拒绝，不落协议行**。

### 6.2 `channel.agent.pay` — 代扣执行（幂等）

| 入参 | 必填 | 说明 |
|---|---|---|
| `signNo` | 是 | 协议号 |
| `amount` | 是 | 扣款金额（分，>0 且 ≤1e12） |
| `requestId` | 是 | 幂等键（`sign_deduct.request_id` UNIQUE） |
| `merchantId` | 否 | 非空时校验与协议授权商户一致（授权范围） |
| `subject` | 否 | 交易标题，缺省「代扣-{signNo}」 |
| `callbackUrl` | 否 | 缺省用协议 `callbackUrl`；经既有 payment→notify 投递 `PAY_SUCCESS`（**支持 http**） |

**返回** `SignDeductDTO`（裸数据）：
```json
{"deductNo":"D...","signNo":"S...","accountId":"A...","merchantId":"M...","requestId":"...",
 "attemptRequestId":"D...:A1","orderId":"O...","paymentId":"P...","amount":300,"status":"SUCCESS",
 "attempts":1,"maxRetry":3,"nextRetryAt":null,"failReason":null,"periodKey":"20261120",
 "channelCode":"MOCK_BANK","createdAt":1795152000000,"updatedAt":1795152000000,"finishedAt":1795152000000,
 "repeated":false}
```
- **不新造资金路径**：经 `TradeFacade.createOrder` + `PaymentFacade.pay(method=CARD)` 走既有受理/风控/手续费/TCC/复式记账/五层幂等；
  渠道侧复用 `ChannelAdapter#agentPay`。**手续费口径不变**（`feeBps=60` 四舍五入 → `SYS_FEE`）。
- 状态机 `INIT → PROCESSING → SUCCESS`（异步渠道）/ `INIT → SUCCESS`（同步渠道）；失败 `→ FAILED`（可重试）`→ TERMINATED`。
- **幂等**：同 `requestId` 返回首次扣款单 + `repeated=true`；每次尝试确定性键 `deductNo:A<n>`（支付单 `request_id` UNIQUE）⇒ **绝不重复扣款**。
  因此**同键重放即查询**，可用于轮询终态（SDK `wait_deduct_terminal` / `waitDeductTerminal`）。
- **拒绝在受理前不落悬挂单**：协议非 SIGNED/不在有效期/非扣款日/商户不符/超单笔限额/超周期累计限额/账户状态异常 →
  只留 `sign_audit` REJECT 痕，**不建 sign_deduct / trade_order / payment / channel_trade 任何行**。
- **错误（与 channel 域 `SignContractService.assertDeductable` / `SignDeductService` 实际口径一致）**：
  缺 `signNo`/`amount`/`requestId` 或 `amount` 非法 → `A0400`(400)；协议/扣款单不存在 → `A0404`(404)；
  协议状态不可代扣（已解约/到期）→ `A0409`(409)；**商户不在授权范围** → `A0403`(403)；
  尚未生效/已过期/非约定扣款日/**超单笔限额**/超周期累计限额/账户非 ACTIVE/账户限额/余额不足 → `B0001`(400)。
  以上拒绝**全部发生在受理前**（只留 `sign_audit` REJECT 痕，不建扣款单/交易单/支付单）。
  沙箱模拟层拒绝码与生产**逐一对齐**，业务方同一套错误处理可切沙箱/生产。

---

## 7. scope / 版本 / 沙箱 / 限流登记（开放面收尾接线）

### 7.1 scope（`OpenApiScopes`，**只对已注册 appId 生效**）

| 方法 | scope | 方法 | scope |
|---|---|---|---|
| `cashier.methods` | `cashier:read` | `cashier.payVerified` | `cashier:pay` |
| `cashier.code.create` | `cashier:code` | `cashier.payPassword.set` | `cashier:password` |
| `cashier.code.parse` | `cashier:read` | `cashier.sms.send` | `cashier:sms` |
| `cashier.code.query` | `cashier:read` | `channel.sign` | `channel:sign` |
| `cashier.scanPay` | `cashier:pay` | `channel.agent.pay` | `channel:agentpay` |
| `cashier.verify` | `cashier:verify` | | |

注册默认 `scopes=["*"]`；显式收窄缺 scope → **403 `A0403`**（受理前）。目录自述：`GET /api/openplatform/scopes`（Bearer）
与 `GET /openapi/version` 的 `scopes` 块（免签名）。**未注册 appId（ride `appM1`）走 legacy 全通配，绝不因新登记被拦**。

### 7.2 版本（`ApiVersionRouter`）

- `/openapi/<method>` **恒为 v1**，请求头 `X-Api-Version` 只回显期望值（`X-Api-Version-Requested`），**不改变 v1 行为**；
- 11 个新方法登记在 `v1.additiveMethods`（**additive**），`v1.methods` 冻结清单（13 个既有方法）与 `frozen=true` 一字未改；
- 弃用公告针对**版本**（`payment.openapi.deprecated-versions`/`payment.openapi.sunset`，默认空 = v1 `STABLE`），
  生效时回显 `X-Api-Deprecated`/`X-Api-Sunset`；
- 公告新增 `multiclient`（四端 H5/MINI_APP/APP/PC、主扫 `code=` 归一口径、验证四型、幂等、轮询、双 SDK、双文档）
  与 `sandbox`（隔离口径、方法目录、语义、注册方式）两块自述。

### 7.3 沙箱（`SandboxRouter` + `SandboxCashierSupport`）

沙箱 appId（`POST /api/openplatform/apps {sandbox:true}` 注册，前缀 `sbx`）调用 **9 个 `cashier.*` + 2 个 `channel.*` 已登记可用**：

- 请求在网关截流，只走 member 域 `openapi_sandbox_account/_txn/_order`（`SBXA/SBXM/SBXO/SBXP`），
  **生产账户/支付单/账务一行不动**；响应头 `X-Api-Sandbox: true`；
- 出参**形状与生产逐字段同名**（`CashierDTO`/`PayCodeDTO`/`VerifyResultDTO`/`PayResultDTO{payment,repeated}`/`SignContractDTO`/`SignDeductDTO`），
  业务方同一套渲染代码可切沙箱/生产；主扫 `qrContent` 同样是扫码 URL（须归一）；
- 沙箱语义：`fee=0`、同步 `SUCCESS`、仅 `BALANCE`、`bizNo/outTradeNo/requestId` 幂等、`freeVerifyLimit=1000`、码 TTL 60s、会话/短信 TTL 300s、
  口令错 5 次锁 15 分钟；签约默认单笔 50000/周期 200000 分、`MOCK_BANK`、有效期 365 天；
- 付款码/口令哈希/短信码/验证会话/签约协议/扣款单为**按 appId 隔离的进程内沙箱态**（联调用，网关重启即清；生产路径由 cashier/channel 域落库）；
- **双向隔离**：沙箱 appId 读生产账户 → 404/403；生产 appId 读沙箱账户 → 404/403。

### 7.4 限流（I-7 Sentinel 热点参数，`paramIdx=0`=appId）

规则文件 `~/.config/payment-pro/sentinel-rules.json`（600；仓库 `config/sentinel-rules.json.example` 同步），网关每 5s 热更新。
**既有 11 条阈值一字未改**，本次 additive 追加 11 条：

| 资源 | QPS/appId | 资源 | QPS/appId |
|---|---|---|---|
| `openapi:cashier.methods` | 30 | `openapi:cashier.payVerified` | **10** |
| `openapi:cashier.code.create` | 10 | `openapi:cashier.payPassword.set` | 5 |
| `openapi:cashier.code.parse` | 30 | `openapi:cashier.sms.send` | 5 |
| `openapi:cashier.code.query` | 50 | `openapi:channel.sign` | **8** |
| `openapi:cashier.scanPay` | **10** | `openapi:channel.agent.pay` | **8** |
| `openapi:cashier.verify` | 30 | | |

阈值与既有冻结面**同口径校准**：资金写 `scanPay`/`payVerified`=10（对齐 `trade.pay`=10）、
`channel.sign`/`agent.pay`=8（对齐 `fund.topup`=8）、`verify`=30（非资金写，对齐只读；覆盖验证四型 + 口令锁定 5 连击突发）、口令/短信=5；
只读 `methods`/`parse`=30、`code.query`=50（对齐 `account.balance`/`trade.query`=50）。

拒绝 → **429 `A0429`**，发生在验签通过后、任何 facade 调用之前（**不落 PROCESSING 悬挂单**）；Sentinel 故障 fail-open。
ride-pro 顺序 outbox <5 QPS、smoke 顺序请求 <3 QPS，正常流量不受影响；端侧遇 429 指数退避后**原键**重试。

---

## 8. 双 SDK / 可跑示例 / 契约自检

| 交付物 | 位置 |
|---|---|
| Python SDK（9 收银台 + 2 签约 + 归一/幂等/轮询工具） | `sdk/python/payment_openapi.py` |
| Java SDK（同能力，JDK 11+ 零依赖） | `sdk/java/src/com/qoder/payment/sdk/PaymentOpenApiClient.java` |
| Python 端到端沙箱示例 | `sdk/python/example_cashier_sandbox.py` |
| Java 端到端沙箱示例 | `sdk/java/src/com/qoder/payment/sdk/CashierSandboxExample.java` |
| Java 契约探针（方法目录 + 归一逐例，无网络） | `sdk/java/src/com/qoder/payment/sdk/ContractProbe.java` |
| 契约一致性自检 | `verify-multiclient.py` |
| 多端适配指南 | `docs/cashier-multiclient.md` |

```bash
# 沙箱应用注册后（一次性返回 appId/appSecret）
PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_cashier_sandbox.py     # 退出码 0
bash sdk/java/build.sh
PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.CashierSandboxExample
java -cp sdk/java/out com.qoder.payment.sdk.ContractProbe                            # 契约探针
python3 verify-multiclient.py                                                        # 契约一致性 + 登记 + 隔离 + legacy
```

示例序列：开户 → 充值(幂等) → 下单 → `cashier.methods` → 设口令 → `verify(PASSWORD)` → `payVerified`
→ 轮询终态 → **同 `requestId` 幂等重放** → 小额免密 → 被扫(核销/幂等/防重放/轮询 `CONSUMED`)
→ 主扫(**`qrContent` 归一 `code=`**) → SMS/BIO → `channel.sign` → `channel.agent.pay`(幂等) → 解约后拒绝代扣。