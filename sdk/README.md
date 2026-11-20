# payment-pro 开放平台 SDK（G-4b + C-5 多端对接能力）

零第三方依赖，签名口径与服务端冻结实现**逐字节一致**：
`TreeMap 排序 k=v&`（剔除 `sign`、跳过 null）+ `HMAC-SHA256` 小写 hex + **毫秒** `timestamp`（±300s）+ **可选** `nonce`（TTL 300s）。
`sign_type=RSA2` 时用 `SHA256withRSA` + Base64。**布尔值 canonical 渲染为小写 `true`/`false`**（与 Java `String.valueOf` 一致）。

> **C-5 口径**：payment-pro **不自建 C 端收银台页面**，只提供**多端对接能力**（OpenAPI + 双 SDK + 契约）。
> 业务方（ride-pro 打车系统及未来 H5/小程序/App/PC 各端）**自渲染收银台**。
> 契约：[`docs/cashier-openapi.md`](../docs/cashier-openapi.md)（9 个 `cashier.*` + 2 个 `channel.*`）；
> 各端适配：[`docs/cashier-multiclient.md`](../docs/cashier-multiclient.md)。

## 目录
- `python/payment_openapi.py` — 客户端 + 签名工具 + 幂等重试 + 错误码映射 + 版本路由
  + **收银台 9 方法 + 签约代扣 2 方法** + `normalize_scan_code`（主扫归一）/ `new_request_id`（幂等键）/ `poll_until`（轮询）
- `python/example_sandbox.py` — 沙箱资金全链路示例（开户/充值/余额/下单/支付/查询/划转/提现）
- `python/example_cashier_sandbox.py` — **C-5 收银台 + 签约代扣端到端沙箱示例**（渲染/验证四型/受理/轮询终态/幂等重放/被扫/主扫归一/签约/代扣/解约）
- `java/src/com/qoder/payment/sdk/` — `Signer`/`Json`/`PaymentOpenApiClient`（含收银台+签约+`normalizeScanCode`/`newRequestId`/`waitXxxTerminal`）/
  `SandboxExample`/`CashierSandboxExample`/`ContractProbe`（JDK 11+）
- `java/build.sh` — `javac` 编译到 `java/out`

## 快速开始（沙箱）
沙箱应用由控制台注册（`POST /api/openplatform/apps`，`sandbox=true`），响应**一次性**返回 `appId`/`appSecret`：

```bash
curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<控制台口令>"}'          # → token
curl -s -X POST http://127.0.0.1:8080/api/openplatform/apps -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"name":"我的沙箱应用","sandbox":true,"unitPrice":0}'
```

Python：
```bash
PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_sandbox.py
PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_cashier_sandbox.py   # C-5 收银台+签约
```

Java：
```bash
bash sdk/java/build.sh
PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.SandboxExample
PP_APP_ID=sbx... PP_APP_SECRET=... java -cp sdk/java/out com.qoder.payment.sdk.CashierSandboxExample
java -cp sdk/java/out com.qoder.payment.sdk.ContractProbe      # 契约探针（方法目录+归一逐例，无网络）
```

## 收银台 + 签约方法集（双 SDK 一一对应服务端端点）

| OpenAPI | Python | Java |
|---|---|---|
| `cashier.methods` | `cashier_methods(order_id, account_id=None)` | `cashierMethods(orderId, accountId)` |
| `cashier.code.create` | `cashier_code_create(type_, account_id, order_id, amount, request_id)` | `cashierCodeCreate(type, accountId, orderId, amount, requestId)` |
| `cashier.code.parse` | `cashier_code_parse(code)` | `cashierCodeParse(code)` |
| `cashier.code.query` | `cashier_code_query(code)` | `cashierCodeQuery(code)` |
| `cashier.scanPay` | `cashier_scan_pay(code, request_id, payer_account_id, ...)` | `cashierScanPay(code, requestId, payerAccountId, method, verifyToken, payPassword, cardNo, callbackUrl)` |
| `cashier.verify` | `cashier_verify(account_id, member_id, order_id, amount, verify_type, ...)` | `cashierVerify(accountId, memberId, orderId, amount, verifyType, payPassword, smsCode, bioToken, requestId)` |
| `cashier.payVerified` | `cashier_pay_verified(order_id, request_id, method, account_id, verify_token, ...)` | `cashierPayVerified(orderId, requestId, method, accountId, verifyToken, payPassword, cardNo, callbackUrl)` |
| `cashier.payPassword.set` | `cashier_pay_password_set(password, account_id, member_id, old_password)` | `cashierPayPasswordSet(password, accountId, memberId, oldPassword)` |
| `cashier.sms.send` | `cashier_sms_send(account_id, member_id, order_id, sandbox)` | `cashierSmsSend(accountId, memberId, orderId, sandbox)` |
| `channel.sign` | `channel_sign(request_id, account_id, merchant_id, card_no, action=..., sign_no=...)` | `channelSign(requestId, accountId, merchantId, cardNo[, options])` / `channelUnsign(signNo, requestId, reason)` |
| `channel.agent.pay` | `channel_agent_pay(sign_no, amount, request_id, merchant_id, subject, callback_url)` | `channelAgentPay(signNo, amount, requestId, merchantId, subject, callbackUrl)` |

**多端工具**：
- `normalize_scan_code(qr)` / `normalizeScanCode(qr)` — **主扫 `qrContent` 是扫码 URL**
  `https://cashier.payment-pro.example/scan?code=<token>&amount=<amt>`，必须从 `code=` 归一取 token 再调 `code.parse`/`scanPay`
  （直接拿整串 URL 当 code → 404）。被扫码值原样返回；`code.parse`/`code.query`/`scanPay` 封装**已自动归一**。
- `new_request_id(prefix)` / `newRequestId(prefix)` — 幂等键（≤64 字符）；**重试必须原样复用同键**，
  推荐订单级确定性键 `PAY-{orderId}`（跨端同单不重复扣款）。
- `wait_order_terminal` / `wait_code_consumed` / `wait_deduct_terminal`（Java `waitOrderTerminal`/`waitCodeConsumed`/`waitDeductTerminal`）
  — 只读回查至终态；代扣用**同 `requestId` 幂等重放**即查询。超时返回最后结果，由调用方断言。

## 版本
- 默认 `v1`（`/openapi/<method>`）：八个冻结方法入参/返回/幂等键/语义不变，`account.balance` 返回**裸数字 long**。
- `api_version="v2"`：`trade.create`/`trade.query`/`account.balance` 走 `/openapi/v2/<method>`，
  响应 envelope `{apiVersion,data}`，SDK 自动解包 `data`；`trade.create` 支持可选 `attach`。
- **`cashier.*` / `channel.*` 恒为 v1 路径 additive 新方法**（登记在公告 `v1.additiveMethods`，冻结清单不变）。
- 协商公告：`GET /openapi/version`（免签名），含弃用状态与 sunset、`scopes` 目录、`multiclient`（四端/归一/验证四型/幂等/轮询）
  与 `sandbox`（隔离/方法目录/语义）；响应头 `X-Api-Version` 回显生效版本。
  **请求头不能把 v1 路径变成 v2 行为**。

## 授权（G-4a，与 HMAC 并存）
`POST /openapi/oauth/token {appId,appSecret,scope?}` → `access_token`；
调用时带 `X-Access-Token` 头做 scope 二次收敛，**业务方法仍必须 HMAC 签名**。
撤销授权（`POST /api/openplatform/apps/{appId}/revoke`）即时生效：调用 403、token 立即失效。

新方法 scope：`cashier:read`（methods/parse/query）、`cashier:code`、`cashier:pay`（scanPay/payVerified）、
`cashier:verify`、`cashier:password`、`cashier:sms`、`channel:sign`、`channel:agentpay`。
注册默认 `*`；**未注册 appId（ride `appM1`）走 legacy：不校验 scope、不限配额、单价 0、不进沙箱**。

## 沙箱与限流
- 沙箱 appId 可调**全部 10 个既有方法 + 9 个收银台 + 2 个签约**；资金只落 `openapi_sandbox_*`（`SBXA/SBXO/SBXP`），
  `fee=0`、同步 SUCCESS、仅 BALANCE，**生产账零污染**；响应头 `X-Api-Sandbox: true`。
  码/口令/会话/协议/扣款单为按 appId 隔离的**进程内沙箱态**（重启即清）。
- 限流（Sentinel 热点参数，appId 维度）：资金写 `scanPay`/`payVerified`/`channel.sign`/`channel.agent.pay` **5 QPS**，
  只读 `methods`/`code.parse`/`verify` 30、`code.query` 50、`code.create` 10、`payPassword.set`/`sms.send` 5；
  `channel.sign`/`channel.agent.pay` 8（对齐 `fund.topup`）。
  拒绝 **429 A0429 在受理前**（不落悬挂单）；规则见 `config/sentinel-rules.json.example`（部署到 `~/.config/payment-pro/`，600）。

## 错误码
`A0400` 参数/验签 · `A0401` token 无效 · `A0403` scope 未授权/授权撤销/验证无效/超额度 · `A0404` 码·订单·账户·协议不存在 ·
`A0409` nonce 重放/码已核销/订单级重复/协议已解约 · `A0429` 限流/日配额 · `B0001` 业务失败（风控「频繁」→ 等窗口重置，不放宽阈值）·
`B0003` 熔断 · `C0001` 下游/网络。SDK 对网络/5xx/B0003 **原键**幂等重试（`bizNo`/`outTradeNo`/`requestId` 不变）。

## 契约自检（防漂移）
```bash
python3 verify-multiclient.py
```
校验：双 SDK 方法目录 ↔ `OpenApiCashierController`(9)/`OpenApiSignController`(2) 实际 `@PostMapping` 端点**双向一一对应** +
逐方法 `call("<api>")` 字面量 + 主扫归一 10 例双 SDK 逐例对齐 + canonical 布尔渲染一致 +
scope/版本/沙箱/限流登记生效 + 沙箱双向隔离 + 签约 2 方法实签 + **appM1 形态 legacy 不误拦** +
**`OpenApiController` md5 冻结未变**。