# C-5 多端收银台对接指南（H5 / 小程序 / App / PC）

> **口径（用户明确指令）**：C-5 = **多端对接能力**，不是 payment-pro 自建 C 端页面。
> payment-pro 只提供 **开放 OpenAPI + 双 SDK + 契约文档**；收银台 UI 由**业务方自渲染**
> （首个接入方：ride-pro 打车系统，已完成乘客端 6 视图 + 纯 JS QR 零 CDN）。
> 原 F 泳道 H5 原型保留在 `ui-design/h5-prototype/`（**仅设计参照，网关不再服务 `/h5/**`，访问 404**）。
>
> **本文回答**：各端怎么调、二维码怎么画、验证四型怎么交互、`requestId` 怎么生成、错误怎么重试、终态怎么轮询。
> 方法级契约见 [`cashier-openapi.md`](cashier-openapi.md)（9 个 `cashier.*` + 2 个 `channel.*`）。

---

## 1. 能力面（业务方拿到的东西）

| 交付物 | 位置 | 说明 |
|---|---|---|
| 收银台 9 方法 | `POST /openapi/cashier.*` | `OpenApiCashierController`（additive，HMAC 免 Bearer） |
| 签约代扣 2 方法 | `POST /openapi/channel.sign`、`/openapi/channel.agent.pay` | `OpenApiSignController`（additive，复用 `SignFacade` 既有能力） |
| 既有商户 13 方法 | `POST /openapi/<method>` | **v1 冻结**：入参/返回/幂等键/语义逐字节不变 |
| Python SDK | `sdk/python/payment_openapi.py` | 零第三方依赖；签名/重试/错误映射/版本路由 + 收银台 + 签约 + 归一工具 |
| Java SDK | `sdk/java/src/com/qoder/payment/sdk/` | JDK 11+ 内置 HttpClient，零第三方依赖；同上能力 |
| 可跑示例 | `sdk/python/example_cashier_sandbox.py`、`sdk/java/.../CashierSandboxExample.java` | 端到端沙箱序列，退出码 0 |
| 契约自检 | `verify-multiclient.py` | SDK 方法集 ↔ 服务端端点一一对应、归一逐例对齐、md5 冻结校验 |
| 能力公告 | `GET /openapi/version`（免签名） | `multiclient` / `sandbox` / `scopes` / `v1.additiveMethods` 四块自述 |

**统一受理原则**：各端**共用同一套方法与同一份返回结构**，端差异只在渲染与交互；
服务端不因端标识改变受理语义（`clientType` 可作为 additive 透传参数留痕，参与签名、不影响结果）。

---

## 2. 各端适配矩阵

| 端 | 调用位置 | 二维码 | 验证交互 | 幂等键存储 | 终态获取 |
|---|---|---|---|---|---|
| **H5**（浏览器/WebView） | **业务方后端**代调 OpenAPI（`appSecret` 绝不下发前端）；前端只调业务方自己的 BFF | 前端绘 QR（主扫）/条码+QR（被扫），纯 JS 零 CDN 可行 | 口令键盘/短信码输入/`WebAuthn` 采集 bioToken | BFF 侧持久化 `requestId`（订单级） | BFF 轮询 `trade.query` 后推送/前端轮询 BFF |
| **小程序**（微信/支付宝） | 业务方后端代调；小程序 `request` 只连业务方域名（域名白名单） | `canvas` 绘 QR；主扫需**归一 `code=`** 后再调 | 口令自定义键盘（禁用系统截屏）/短信码/`startSoterAuthentication` → bioToken | 后端持久化；小程序侧只存订单号 | 后端轮询 + `onShow` 回查（切后台回来必须回查，不可信本地状态） |
| **App**（iOS/Android） | 业务方后端代调；App 侧可用 SDK 直连**仅限沙箱联调** | 原生 QR 渲染（`CIQRCodeGenerator`/ZXing）；扫一扫结果**必须归一** | 系统生物识别（`LocalAuthentication`/`BiometricPrompt`）→ bioToken；口令用安全键盘 | 后端持久化 + 本地订单号映射 | 后端轮询 + 回调（`callbackUrl` 支持 http）+ 前台回查 |
| **PC**（Web 收银台/商户端） | 业务方后端代调 | 大屏 QR（主扫）；被扫用扫码枪读 18 位数字（**扫码枪可能输出整串 URL，同样归一**） | 口令键盘/短信码；PC 无生物识别 → 走 PASSWORD/SMS | 后端持久化 | 后端轮询 + WebSocket/SSE 推前端 |

**共同红线**：
1. `appSecret`/支付口令/短信明文码**只在业务方服务端**，不下发任何端；
2. 端侧**不得自行判定支付成功**——一律以服务端终态回查为准（前端补偿只管展示）；
3. 同一订单同一 `requestId` 跨端复用（用户在 H5 下单、App 支付也是同一单，不重复扣款）。

---

## 3. 二维码渲染要求（C-2）

### 3.1 主扫（`MERCHANT_PRESENTED`，商户出码、用户扫）

`cashier.code.create` 返回的 **`qrContent` 是扫码 URL，不是码值本身**：

```
https://cashier.payment-pro.example/scan?code=MP<32hex>&amount=<分>
```

- **渲染**：把 `qrContent` **整串**编进 QR（不要只编 token，否则扫码侧拿不到金额上下文）；
- **扫码侧**：拿到 URL 后**必须从 `code=` 查询参数归一取出 token**，再调 `cashier.code.parse` / `cashier.scanPay`；
  直接拿整串 URL 当 `code` 调用 → **404 `A0404` 付款码不存在**。
- **归一工具（双 SDK 已内置，各端直接用）**：

```python
from payment_openapi import normalize_scan_code
token = normalize_scan_code(pay_code["qrContent"])      # → MP<32hex>
client.cashier_scan_pay(token, request_id, payer_account_id=payer)
```

```java
String token = PaymentOpenApiClient.normalizeScanCode(qrContent);   // → MP<32hex>
client.cashierScanPay(token, requestId, payerAccountId, "BALANCE", null, null, null, null);
```

归一函数兼容：参数顺序任意、URL 编码（`%61`→`a`）、`#fragment`、额外查询参数、无 scheme 的裸查询串
（扫码枪常见输出）、前后空白；**被扫纯码值原样返回**（恒等），无 `code=` 的 URL 原样返回（不误造 token）。
`cashier.code.parse` / `cashier.code.query` / `cashier.scanPay` 的 SDK 封装**已自动归一**，可直接传 `qrContent`。

### 3.2 被扫（`USER_PRESENTED`，用户出示、商户扫）

- `code` = **18 位数字**，`qrContent` **等于码值本身**（同一串既可绘条码也可绘 QR）；
- `displayCode` = 4 位分组展示串（如 `1515 9175 3762 2717 73`），**仅供人眼核对，不可用于受理**；
- 建议同时绘 **Code128 条码 + QR**（扫码枪/摄像头双通道）。

### 3.3 渲染质量与时效

| 项 | 要求 |
|---|---|
| 尺寸 | 主扫 QR ≥ 240×240 px（PC ≥ 320），被扫条码宽 ≥ 280 px |
| 容错 | QR 纠错等级 ≥ M（建议 Q）；**静区 ≥ 4 模块**，勿叠 logo 覆盖定位点 |
| 对比度 | 深色模块/浅色底，避免渐变与反色（部分扫码器不支持反色） |
| 依赖 | **零 CDN 可行**：ride-pro 已用纯 JS QR 编码落地（离线/内网可用），小程序用 canvas |
| 时效 | TTL 默认 **60s**；端侧必须显示倒计时（`ttlSeconds`），到期置灰并提供「刷新码」 |
| 刷新 | 到期后**重新 `code.create`**（可带新 `requestId`）；旧码 `EXPIRED` 不可受理（409） |
| 安全 | 码值不含账户口令等敏感信息；**不要截屏上传/日志打印完整码值**（日志只留后 4 位） |

---

## 4. 验证四型交互（C-3）

`cashier.verify` → `VerifyResultDTO{pass,challengeType,verifyToken,sessionId,expireAt,reason,freeLimit,remainAttempts,lockedUntil}`。

| verifyType | 端侧采集 | 入参 | 交互要求 |
|---|---|---|---|
| `PASSWORD`（默认） | 6 位数字支付口令 | `payPassword` | 自定义安全键盘（禁截屏/禁明文回显）；首次使用引导 `cashier.payPassword.set` |
| `SMS` | 短信验证码 | `smsCode`（先 `cashier.sms.send`） | 60s 重发倒计时；**生产默认掩码 `******`，端侧不得依赖响应取码**（`sandbox=true` 仅联调） |
| `BIO` | 系统生物识别 | `bioToken`（端侧采集令牌） | iOS `LocalAuthentication` / Android `BiometricPrompt` / 小程序 `startSoterAuthentication` / H5 `WebAuthn`；失败降级 PASSWORD |
| `SMALL_AMOUNT_FREE` | 无 | `amount ≤ freeVerifyLimit` | 免密直接受理；超限返回 `pass=false` + `challengeType=PASSWORD`，端侧转口令流程 |

**端侧必须处理的状态**：

| 返回 | 端侧动作 |
|---|---|
| `pass=true` + `verifyToken` | 立即携令牌调 `cashier.payVerified`/`cashier.scanPay`；令牌**一次性**且绑定 `orderId+amount+payer`，TTL 300s |
| `pass=false`, `challengeType=NEED_SET_PASSWORD` | 引导设置口令（`cashier.payPassword.set`），设置后重试 verify |
| `pass=false`, `challengeType=LOCKED` + `lockedUntil` | **展示锁定倒计时，禁止无限重试**（默认错 5 次锁 15 分钟） |
| `pass=false`, `remainAttempts=n` | 提示「还可试 n 次」，n=0 转锁定态 |
| `pass=false`, `challengeType=SMS/BIO` | 按 `reason` 提示重采（短信码错误/未采集到令牌） |

**红线**：验证失败**不受理、不落单、不扣款**；`verifyToken` 复用/跨单/跨金额 → 受理侧 **403 `A0403`**。
`amount ≤ freeVerifyLimit`（默认 1000 分）时 `payVerified` 可不带任何验证凭据。

---

## 5. 幂等 `requestId` 生成建议

```python
from payment_openapi import new_request_id
rid = new_request_id("PAY")        # PAY-<uuid32>，≤64 字符
```
```java
String rid = PaymentOpenApiClient.newRequestId("PAY");   // PAY-<uuid32>
```

| 场景 | 推荐键 | 理由 |
|---|---|---|
| 订单支付（各端共用） | **`PAY-{orderId}`**（确定性） | 跨端/跨会话天然同键；ride-pro 即用此口径，重放返回同一支付单 |
| 扫码受理 | `SCAN-{code后4位}-{orderId}` 或 `new_request_id("SCAN")` | 同码同键幂等；**异键同码 → 409 防重放** |
| 签约 | `SIGN-{accountId}-{merchantId}` | 与服务端三元组幂等 `(SIGN,accountId:merchantId:channelCode)` 对齐 |
| 代扣 | `DEDUCT-{signNo}-{账期}`（如 `DEDUCT-S123-202611`） | 一个账期一次扣款，天然防重 |
| 资金单 | `bizNo`（既有口径不变） | `fund.topup/withdraw`、`account.transfer` 冻结键 |

**硬规则**：
1. **重试必须原样复用同一 `requestId`**（双 SDK 的网络/5xx/B0003 自动重试已保证）；换键重试 = 新请求，可能重复扣款；
2. `requestId` 必须**持久化在业务方服务端**（与订单同事务落库），端侧只传订单号；
3. 同键重放返回**同一单** + `repeated=true`，端侧据此展示「已支付」而非再发起一次；
4. 订单级重复（同订单异 `requestId`）→ **409 `A0409`**，端侧应回查原支付单而不是重试新键。

---

## 6. 错误处理与重试矩阵

| code | HTTP | 端侧/SDK 动作 | 可重试 |
|---|---|---|---|
| `A0400` | 400 | 参数/验签问题：**修代码不重试**（canonical 漏签、空串、秒级 timestamp、键序、URL encode、布尔大小写） | 否 |
| `A0403` | 403 | 验证无效/过期/已用、超免密未验证、旧口令错、协议失效/超额度：**重新验证或引导用户**，不原样重试 | 否（需新凭据） |
| `A0404` | 404 | 码/订单/账户/协议不存在：核对 id（**主扫先归一 `code=`**）；商户/用户**未开户**先 `account.open` | 否 |
| `A0409` | 409 | 码已过期/已核销、订单级重复、nonce 重放、**协议已解约/到期不可代扣**：**回查原单/重新生码/引导重签**，绝不换键硬重试 | 否 |
| `A0429` | 429 | 限流/配额：**指数退避**（1s→2s→4s，≤3 次）后原键重试；持续超限联系平台调阈值 | 是（退避） |
| `B0001` | 400 | 业务失败（余额不足/风控拦截/**代扣超限额·非扣款日·协议未生效或已过期**）：提示用户；文案含「频繁」= 风控滑动窗口 → **等窗口重置（约 10s）重试，不得放宽阈值** | 视原因 |
| `B0002` | 500 | 系统内部错误：原键幂等重试（SDK 自动 ≤2 次） | 是 |
| `B0003` | 503 | 熔断降级：退避后原键重试 | 是 |
| `C0001` | 502/网络 | 下游/网络异常：**原键重试**（可能已受理，重试由幂等键吸收） | 是 |

**超时未知态处理（重要）**：网络超时**不代表失败**——必须用**同一 `requestId` 重放**或 `trade.query` 回查确认，
严禁换键重发。SDK 已对网络异常/5xx/B0003 做原键重试（Python `retries=2`，Java 同）。

---

## 7. 轮询终态（各端统一）

| 对象 | 轮询方法 | 终态集合 | 建议节奏 |
|---|---|---|---|
| 支付单/订单 | `trade.query`（或业务方支付单查询） | `SUCCESS`/`FAILED`/`CLOSED`/`REFUNDED` | 间隔 0.5s，超时 30s；**BALANCE 同步受理，通常首次即终态** |
| 付款码（被扫端） | `cashier.code.query` | `CONSUMED`/`EXPIRED` | 间隔 1s，TTL 60s 内；`EXPIRED` → 重新生码 |
| 代扣单 | **`channel.agent.pay` 同 `requestId` 重放**（幂等即查询） | `SUCCESS`/`FAILED`/`TERMINATED` | 间隔 1s，超时 30s；`FAILED` 可走控制台重试 |

SDK 助手：`wait_order_terminal` / `wait_code_consumed` / `wait_deduct_terminal`（Python），
`waitOrderTerminal` / `waitCodeConsumed` / `waitDeductTerminal`（Java）。**超时返回最后一次结果，由调用方断言，绝不静默成功**。

**红线**：`BALANCE`/资金单（无渠道分支）**同步受理 → PROCESSING → SUCCESS**，
**不得引入 WAITING/异步回执**；`CARD` 异步渠道才可能中间态，端侧需继续轮询。
回调 `callbackUrl` **继续支持 http**（body 结构不变），但**回调只做加速，终态仍以回查为准**。

---

## 8. 沙箱联调（不污染生产）

```bash
# 1) 注册沙箱应用（控制台，Bearer）→ 响应一次性返回 appId/appSecret
curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<控制台口令>"}'
curl -s -X POST http://127.0.0.1:8080/api/openplatform/apps -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"name":"我的沙箱应用","sandbox":true,"unitPrice":0}'

# 2) 跑双 SDK 端到端示例（退出码 0 = 全断言通过）
PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_cashier_sandbox.py
bash sdk/java/build.sh
PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.CashierSandboxExample
```

示例序列（两端一致）：开户 → 充值(幂等) → 下单 → `cashier.methods` 渲染 → 设口令 → `verify(PASSWORD)`
→ `payVerified` → 轮询终态 → **同键幂等重放** → 小额免密 → 被扫(核销/幂等/防重放/轮询 CONSUMED)
→ 主扫(**归一 `code=`**) → SMS/BIO → `channel.sign` → `channel.agent.pay`(幂等) → 解约后拒绝代扣。

**隔离口径**：沙箱 appId 请求在网关 `SandboxRouter` 截流，只走 member 域 `openapi_sandbox_account/_txn/_order`
（id 前缀 `SBXA/SBXM/SBXO/SBXP`，`fee=0`、同步 SUCCESS、仅 BALANCE）；**生产账户/支付单/账务一行不动**。
付款码/口令哈希/短信码/验证会话/签约协议/扣款单是**按 appId 隔离的进程内沙箱态**（联调用，网关重启即清；
生产路径由 cashier/channel 域落库）。沙箱响应头回显 `X-Api-Sandbox: true`。
沙箱 appId 读生产账户 → 404/403；生产 appId 读沙箱账户 → 404/403（双向隔离，`verify-multiclient.py` 已断言）。

---

## 9. scope / 版本 / 限流（开放面收尾接线）

### 9.1 scope 登记（`OpenApiScopes`，注册应用才校验）

| 方法 | scope | 方法 | scope |
|---|---|---|---|
| `cashier.methods` | `cashier:read` | `cashier.payVerified` | `cashier:pay` |
| `cashier.code.create` | `cashier:code` | `cashier.payPassword.set` | `cashier:password` |
| `cashier.code.parse` | `cashier:read` | `cashier.sms.send` | `cashier:sms` |
| `cashier.code.query` | `cashier:read` | `channel.sign` | `channel:sign` |
| `cashier.scanPay` | `cashier:pay` | `channel.agent.pay` | `channel:agentpay` |
| `cashier.verify` | `cashier:verify` | | |

- 注册默认 `scopes=["*"]`（全通配）；显式收窄时缺 scope → **403 `A0403`**（受理前拒绝）；
- **未注册 appId（如 ride-pro `appM1`）走 legacy**：不校验 scope、不限配额、单价 0、不进沙箱 —— **新登记不影响历史商户**。

### 9.2 版本（`ApiVersionRouter`）

- `/openapi/<method>` **恒为 v1**；请求头 `X-Api-Version` **不可能把 v1 变成 v2 行为**（只回显期望值）；
- v2 只能走显式路径 `/openapi/v2/<method>`（现仅 `trade.create`/`trade.query`/`account.balance`）；
- **11 个新方法为 additive**：登记在 `v1.additiveMethods`，**`v1.methods` 冻结清单（13 个）一字未改**；
- 弃用公告只针对**版本**（`payment.openapi.deprecated-versions` / `sunset` 可配，默认空 = v1 STABLE），不针对方法；
- 公告新增 `multiclient`（四端/归一/验证四型/幂等/轮询/SDK/文档）与 `sandbox`（隔离/方法目录/语义）两块自述。

### 9.3 限流（I-7 Sentinel 热点参数，`paramIdx=0`=appId）

规则文件：`~/.config/payment-pro/sentinel-rules.json`（600，仓库留 `config/sentinel-rules.json.example`），
网关每 5s 热更新；**拒绝发生在验签通过后、业务受理前 → 不落悬挂支付单**；Sentinel 自身故障 fail-open。

| 资源 | QPS/appId | 资源 | QPS/appId |
|---|---|---|---|
| `openapi:cashier.methods` | 30 | `openapi:cashier.payVerified` | **10** |
| `openapi:cashier.code.create` | 10 | `openapi:cashier.payPassword.set` | 5 |
| `openapi:cashier.code.parse` | 30 | `openapi:cashier.sms.send` | 5 |
| `openapi:cashier.code.query` | 50 | `openapi:channel.sign` | **8** |
| `openapi:cashier.scanPay` | **10** | `openapi:channel.agent.pay` | **8** |
| `openapi:cashier.verify` | 30 | | |

阈值口径：与既有冻结面**同口径校准**——资金写 `scanPay`/`payVerified`=10（对齐 `trade.pay`=10）、
`channel.sign`/`agent.pay`=8（对齐 `fund.topup`=8）、`verify`=30（非资金写，覆盖四型+锁定 5 连击）、口令/短信=5，
均显著低于只读（`methods`/`parse`=30、`code.query`=50）；
ride-pro 顺序 outbox < 5 QPS、smoke 顺序请求 < 3 QPS，**正常流量不受影响**；既有 11 条规则阈值一字未改。
端侧遇 429 按 §6 退避重试（原键）。

---

## 10. 双 SDK 方法 ↔ 服务端端点对照（契约自检口径）

| # | OpenAPI 方法 | 服务端 | Python SDK | Java SDK |
|---|---|---|---|---|
| 1 | `cashier.methods` | `OpenApiCashierController#methods` | `cashier_methods` | `cashierMethods` |
| 2 | `cashier.code.create` | `#createCode` | `cashier_code_create` | `cashierCodeCreate` |
| 3 | `cashier.code.parse` | `#parseCode` | `cashier_code_parse` | `cashierCodeParse` |
| 4 | `cashier.code.query` | `#queryCode` | `cashier_code_query` | `cashierCodeQuery` |
| 5 | `cashier.scanPay` | `#scanPay` | `cashier_scan_pay` | `cashierScanPay` |
| 6 | `cashier.verify` | `#verify` | `cashier_verify` | `cashierVerify` |
| 7 | `cashier.payVerified` | `#payVerified` | `cashier_pay_verified` | `cashierPayVerified` |
| 8 | `cashier.payPassword.set` | `#setPayPassword` | `cashier_pay_password_set` | `cashierPayPasswordSet` |
| 9 | `cashier.sms.send` | `#sendSms` | `cashier_sms_send` | `cashierSmsSend` |
| 10 | `channel.sign` | `OpenApiSignController#sign` | `channel_sign` | `channelSign` / `channelUnsign` |
| 11 | `channel.agent.pay` | `OpenApiSignController#agentPay` | `channel_agent_pay` | `channelAgentPay` |

工具函数：`normalize_scan_code` ↔ `normalizeScanCode`（主扫归一）、`new_request_id` ↔ `newRequestId`（幂等键）、
`poll_until` ↔ `waitXxxTerminal`（轮询）。目录常量：`CASHIER_METHODS`/`SIGN_METHODS` ↔ `cashierMethodCatalog()`/`signMethodCatalog()`。

**防漂移**：`python3 verify-multiclient.py` 会解析两个控制器的 `@PostMapping` 实际端点，与双 SDK 目录常量
**双向集合比对** + 逐方法 `call("<api>")` 字面量核对 + 归一函数 10 例逐例对齐 + `OpenApiController` **md5 冻结校验**。
新增/改名方法若未同步 SDK 或文档，脚本立即失败。

---

## 11. 兼容红线（payment 侧承诺，各端可依赖）

1. 既有 11 个商户方法 + HMAC canonical **逐字节不变**（`OpenApiController` 本体零编辑，md5 可核）；
2. `account.balance` 仍返回**裸数字 long**；`BALANCE`/资金单**同步终态**，无 WAITING/异步回执；
3. 手续费口径不变（`feeBps=60` → `SYS_FEE`，四舍五入）；`callbackUrl` 继续支持 **http**，body 结构不变；
4. `nonce` 可选（不传完全跳过）；timestamp **毫秒** ±300s；
5. **未注册 appId（ride `appM1`）legacy 免费、不限流、不限配额、不进沙箱**，新 scope/版本/沙箱/限流登记全部 additive；
6. payment-pro **不自建 C 端页面**：`/h5/**` 已摘除（404），原型仅存 `ui-design/h5-prototype/` 作参照。