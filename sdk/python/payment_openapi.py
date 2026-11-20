#!/usr/bin/env python3
"""payment-pro 开放平台 Python SDK（G-4b）。

零第三方依赖（标准库 urllib/hmac/hashlib）。签名口径与服务端冻结实现逐字节一致：
  canonical = TreeMap 排序 k=v&（剔除 sign、跳过 None）
  sign      = HMAC-SHA256(appSecret, canonical) 小写 hex
  timestamp = 毫秒；±300s 窗
  nonce     = 可选（不传即跳过，ride-pro 兼容红线）
  sign_type = RSA2 时用 SHA256withRSA + Base64（需 cryptography，可选）

C-5 多端对接能力：本 SDK 同时封装 **9 个收银台方法**（cashier.*）与 **2 个签约代扣方法**
（channel.sign / channel.agent.pay），业务方（ride-pro 及未来 H5/小程序/App/PC 各端）
自行渲染收银台，payment-pro 只提供开放能力，不自建 C 端页面。
多端适配指南见 docs/cashier-multiclient.md；契约见 docs/cashier-openapi.md。

用法：
    from payment_openapi import PaymentOpenApiClient, normalize_scan_code, new_request_id
    c = PaymentOpenApiClient("http://127.0.0.1:8080", app_id, app_secret)
    acct = c.account_open("OPTEST-用户", "USER")
    c.fund_topup(acct["accountId"], 1000, biz_no="OPTEST-TOPUP-1")
    print(c.account_balance(acct["accountId"]))   # 裸数字 long
    order = c.trade_create("行程", 1500, "OPTEST-ORDER-1")
    view = c.cashier_methods(order["id"], acct["accountId"])       # 渲染支付方式
    v = c.cashier_verify(account_id=acct["accountId"], order_id=order["id"],
                         amount=1500, verify_type="PASSWORD", pay_password="135790")
    rid = new_request_id("PAY")                                    # 幂等键：重试原样不变
    paid = c.cashier_pay_verified(order["id"], rid, account_id=acct["accountId"],
                                  verify_token=v["verifyToken"])
    token = normalize_scan_code(code_dto["qrContent"])             # 主扫 URL → code= 归一

版本：默认 v1（冻结面）；api_version="v2" 时 trade.create/trade.query/account.balance
走 /openapi/v2/*（envelope 响应，SDK 自动解包 data）。cashier.*/channel.* 恒为 v1 路径 additive。
"""
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

__all__ = ["canonical", "sign_params", "OpenApiError", "PaymentOpenApiClient", "poll_until",
           "normalize_scan_code", "new_request_id", "FROZEN_METHODS", "CASHIER_METHODS",
           "SIGN_METHODS", "TERMINAL_PAYMENT_STATUS", "TERMINAL_DEDUCT_STATUS"]

V2_METHODS = {"trade.create", "trade.query", "account.balance"}

# ---- 契约单一事实来源（verify-multiclient.py 据此与服务端实际端点做一一对应自检，防漂移）----
FROZEN_METHODS = ("account.open", "fund.topup", "fund.withdraw", "account.transfer",
                  "account.balance", "trade.create", "trade.pay", "trade.query")
CASHIER_METHODS = ("cashier.methods", "cashier.code.create", "cashier.code.parse",
                   "cashier.code.query", "cashier.scanPay", "cashier.verify",
                   "cashier.payVerified", "cashier.payPassword.set", "cashier.sms.send")
SIGN_METHODS = ("channel.sign", "channel.agent.pay")
TERMINAL_PAYMENT_STATUS = ("SUCCESS", "FAILED", "CLOSED", "REFUNDED", "PARTIAL_REFUNDED")
TERMINAL_DEDUCT_STATUS = ("SUCCESS", "FAILED", "TERMINATED")


def _render(value):
    """canonical 值渲染：与 Java String.valueOf 逐字节一致（布尔小写 true/false）。"""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def canonical(params):
    """TreeMap 排序 k=v&（剔除 sign、跳过 None）——与服务端 OpenApiController#verify 一致。"""
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    return "&".join(f"{k}={_render(v)}" for k, v in items)


def normalize_scan_code(qr_content):
    """C-5 主扫二维码归一工具（**各端必须调用**）。

    主扫（MERCHANT_PRESENTED）的 ``qrContent`` 是扫码 URL 而非码值本身：
        https://cashier.payment-pro.example/scan?code=<token>&amount=<amount>
    用户扫码后须从 ``code=`` 查询参数取出 token 再调 cashier.code.parse / cashier.scanPay；
    直接拿整串 URL 当 code 会 404。被扫（USER_PRESENTED）的 qrContent 等于码值本身，原样返回。

    兼容：纯码值（18 位数字 / MP 令牌）、含额外查询参数、URL 编码、前后空白、fragment。
    """
    if qr_content is None:
        return None
    text = str(qr_content).strip()
    if not text:
        return text
    try:
        parts = urllib.parse.urlsplit(text)
    except ValueError:
        return text
    if parts.scheme and parts.query:
        query = urllib.parse.parse_qs(parts.query, keep_blank_values=True)
        values = query.get("code")
        if values and values[0]:
            return values[0].strip()
    if "code=" in text:                     # 无 scheme 的裸查询串（扫码枪常见输出）
        query = urllib.parse.parse_qs(text.split("?", 1)[-1], keep_blank_values=True)
        values = query.get("code")
        if values and values[0]:
            return values[0].strip()
    return text


def new_request_id(prefix="PAY"):
    """幂等 requestId 生成建议：业务前缀 + uuid（≤64 字符，全局唯一）。

    **重试必须原样复用同一 requestId**（SDK 网络/5xx 重试已保证），
    换键重试等于新请求 → 可能重复扣款。推荐前缀：PAY-/SCAN-/SIGN-/DEDUCT-。
    """
    return f"{prefix}-{uuid.uuid4().hex}"[:64]


def sign_params(params, app_secret, nonce=None, sign_type=None, rsa_private_key_pem=None):
    """补 appId 之外的公共参数并签名，返回可直接 POST 的 dict。"""
    body = dict(params)
    body["timestamp"] = int(time.time() * 1000)
    if nonce:
        body["nonce"] = nonce
    if sign_type:
        body["sign_type"] = sign_type
    data = canonical(body)
    if sign_type and sign_type.upper() == "RSA2":
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        key = serialization.load_pem_private_key(rsa_private_key_pem.encode(), password=None)
        import base64
        body["sign"] = base64.b64encode(key.sign(data.encode(), padding.PKCS1v15(), hashes.SHA256())).decode()
    else:
        body["sign"] = hmac.new(app_secret.encode(), data.encode(), hashlib.sha256).hexdigest()
    return body


class OpenApiError(Exception):
    """错误码映射（A0400 参数 / A0401 未授权 / A0403 越权或撤销 / A0409 重放 / A0429 限流配额 / B0003 熔断）。"""

    def __init__(self, code, message, status=None, trace_id=None):
        super().__init__(f"[{code}] {message} (http={status}, traceId={trace_id})")
        self.code = code
        self.message = message
        self.status = status
        self.trace_id = trace_id

    @property
    def retryable(self):
        return self.code in ("B0003", "C0001", "C0002") or (self.status or 0) >= 500


class PaymentOpenApiClient:
    def __init__(self, base_url, app_id, app_secret, api_version="v1", timeout=20,
                 retries=2, use_nonce=False, sandbox=False):
        self.base_url = base_url.rstrip("/")
        self.app_id = app_id
        self.app_secret = app_secret
        self.api_version = api_version
        self.timeout = timeout
        self.retries = retries
        self.use_nonce = use_nonce
        self.sandbox = sandbox
        self.access_token = None
        self.last_headers = {}

    # ---------- 授权（G-4a：token 与 HMAC 并存，token 管授权 scope） ----------

    def token(self, scope=None):
        body = {"appId": self.app_id, "appSecret": self.app_secret}
        if scope:
            body["scope"] = scope
        status, data, _ = self._http("POST", "/openapi/oauth/token", body)
        if status != 200:
            raise OpenApiError(data.get("code", "B0002"), data.get("error", "token 颁发失败"), status)
        self.access_token = data["access_token"]
        return data

    def introspect(self, token=None):
        status, data, _ = self._http("POST", "/openapi/oauth/introspect", {"token": token or self.access_token})
        return data

    def version(self):
        status, data, _ = self._http("GET", "/openapi/version?appId=" + self.app_id, None)
        return data

    # ---------- 冻结 v1 八方法（入参名/返回字段/幂等键不变） ----------

    def account_open(self, name, type_="USER"):
        return self.call("account.open", name=name, type=type_)

    def account_balance(self, account_id):
        return self.call("account.balance", accountId=account_id)

    def fund_topup(self, account_id, amount, biz_no, callback_url=None):
        return self.call("fund.topup", accountId=account_id, amount=amount, bizNo=biz_no, callbackUrl=callback_url)

    def fund_withdraw(self, account_id, amount, biz_no, callback_url=None):
        return self.call("fund.withdraw", accountId=account_id, amount=amount, bizNo=biz_no, callbackUrl=callback_url)

    def account_transfer(self, from_account_id, to_account_id, amount, biz_no):
        return self.call("account.transfer", fromAccountId=from_account_id, toAccountId=to_account_id,
                         amount=amount, bizNo=biz_no)

    def trade_create(self, subject, amount, out_trade_no, **extra):
        return self.call("trade.create", subject=subject, amount=amount, outTradeNo=out_trade_no, **extra)

    def trade_pay(self, order_id, account_id, method="BALANCE", callback_url=None, out_trade_no=None):
        return self.call("trade.pay", orderId=order_id, accountId=account_id, method=method,
                         callbackUrl=callback_url, outTradeNo=out_trade_no)

    def trade_query(self, order_id):
        return self.call("trade.query", orderId=order_id)

    # ---------- C-5 收银台 9 方法（多端对接能力，业务方自渲染；契约 docs/cashier-openapi.md） ----------

    def cashier_methods(self, order_id, account_id=None):
        """支付方式渲染：返回 CashierDTO{order,methods[],actualAmount,freeVerifyLimit,currency}。"""
        return self.call("cashier.methods", orderId=order_id, accountId=account_id)

    def cashier_code_create(self, type_, account_id=None, order_id=None, amount=None, request_id=None):
        """生成付款码/收款码：type_=USER_PRESENTED(被扫)/MERCHANT_PRESENTED(主扫)。

        主扫返回的 qrContent 是扫码 URL，绘 QR 后扫码侧须 normalize_scan_code() 归一取 token。
        """
        return self.call("cashier.code.create", type=type_, accountId=account_id, orderId=order_id,
                         amount=amount, requestId=request_id)

    def cashier_code_parse(self, code):
        """解析码（只读，不核销）；入参可以是 qrContent 归一后的 token。"""
        return self.call("cashier.code.parse", code=normalize_scan_code(code))

    def cashier_code_query(self, code):
        """查询码状态（轮询 ACTIVE→CONSUMED/EXPIRED，ttlSeconds 供倒计时）。"""
        return self.call("cashier.code.query", code=normalize_scan_code(code))

    def cashier_scan_pay(self, code, request_id, payer_account_id=None, method=None,
                         verify_token=None, pay_password=None, card_no=None, callback_url=None):
        """扫码受理（一次性核销 + 验证前置 + 幂等支付）；主扫必传 payer_account_id。"""
        return self.call("cashier.scanPay", code=normalize_scan_code(code), requestId=request_id,
                         payerAccountId=payer_account_id, method=method, verifyToken=verify_token,
                         payPassword=pay_password, cardNo=card_no, callbackUrl=callback_url)

    def cashier_verify(self, account_id=None, member_id=None, order_id=None, amount=None,
                       verify_type=None, pay_password=None, sms_code=None, bio_token=None,
                       request_id=None):
        """支付验证四型：PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE → 一次性 verifyToken。"""
        return self.call("cashier.verify", accountId=account_id, memberId=member_id, orderId=order_id,
                         amount=amount, verifyType=verify_type, payPassword=pay_password,
                         smsCode=sms_code, bioToken=bio_token, requestId=request_id)

    def cashier_pay_verified(self, order_id, request_id, method=None, account_id=None,
                             verify_token=None, pay_password=None, card_no=None, callback_url=None):
        """验证前置受理（幂等）：amount ≤ freeVerifyLimit 可免验证；BALANCE 同步终态。"""
        return self.call("cashier.payVerified", orderId=order_id, requestId=request_id, method=method,
                         accountId=account_id, verifyToken=verify_token, payPassword=pay_password,
                         cardNo=card_no, callbackUrl=callback_url)

    def cashier_pay_password_set(self, password, account_id=None, member_id=None, old_password=None):
        """设置/重置支付口令（6 位数字）；返回裸 true，口令只入不出。"""
        return self.call("cashier.payPassword.set", accountId=account_id, memberId=member_id,
                         password=password, oldPassword=old_password)

    def cashier_sms_send(self, account_id=None, member_id=None, order_id=None, sandbox=None):
        """发送短信验证码：默认掩码 ******；仅 sandbox=True 联调返回明文（生产不得依赖响应取码）。"""
        return self.call("cashier.sms.send", accountId=account_id, memberId=member_id,
                         orderId=order_id, sandbox=sandbox)

    # ---------- CH-6 签约代扣 2 方法（OpenAPI 开放面，复用 SignFacade 既有能力） ----------

    def channel_sign(self, request_id, account_id=None, merchant_id=None, card_no=None,
                     channel_code=None, action=None, sign_no=None, reason=None,
                     single_limit=None, period_limit=None, period_type=None,
                     effective_at=None, expired_at=None, deduct_day=None,
                     max_retry=None, retry_interval_ms=None, callback_url=None, scope_note=None):
        """签约/解约：action=SIGN(默认)/UNSIGN。

        SIGN 必填 account_id/merchant_id/card_no/request_id；UNSIGN 必填 sign_no/request_id。
        幂等：同 requestId 或同 (accountId,merchantId,channelCode) 已有 SIGNED 协议 → repeated=true。
        返回 SignContractDTO（卡号只留掩码 + SHA-256 哈希，绝不含明文）。
        """
        return self.call("channel.sign", requestId=request_id, accountId=account_id,
                         merchantId=merchant_id, cardNo=card_no, channelCode=channel_code,
                         action=action, signNo=sign_no, reason=reason, singleLimit=single_limit,
                         periodLimit=period_limit, periodType=period_type, effectiveAt=effective_at,
                         expiredAt=expired_at, deductDay=deduct_day, maxRetry=max_retry,
                         retryIntervalMs=retry_interval_ms, callbackUrl=callback_url, scopeNote=scope_note)

    def channel_agent_pay(self, sign_no, amount, request_id, merchant_id=None, subject=None,
                          callback_url=None):
        """代扣执行（幂等 requestId）：返回 SignDeductDTO{deductNo,status,paymentId,amount,repeated}。

        授权/额度/有效期拒绝一律**受理前拒绝**（不落悬挂单）；同 requestId 重放即查询（不重复扣款）。
        """
        return self.call("channel.agent.pay", signNo=sign_no, amount=amount, requestId=request_id,
                         merchantId=merchant_id, subject=subject, callbackUrl=callback_url)

    # ---------- 多端轮询终态（各端统一：只读回查，绝不重复受理） ----------

    def wait_order_terminal(self, order_id, timeout=30, interval=0.5):
        """轮询 trade.query 至支付终态（BALANCE 同步，通常首次即 SUCCESS）。返回 {order,payment}。"""
        return poll_until(lambda: self.trade_query(order_id),
                          lambda r: isinstance(r, dict) and isinstance(r.get("payment"), dict)
                          and r["payment"].get("status") in TERMINAL_PAYMENT_STATUS,
                          timeout=timeout, interval=interval)

    def wait_code_consumed(self, code, timeout=30, interval=0.5):
        """被扫端轮询付款码 ACTIVE→CONSUMED/EXPIRED（到期需重新生成码）。"""
        return poll_until(lambda: self.cashier_code_query(code),
                          lambda r: isinstance(r, dict) and r.get("status") in ("CONSUMED", "EXPIRED"),
                          timeout=timeout, interval=interval)

    def wait_deduct_terminal(self, sign_no, amount, request_id, timeout=30, interval=0.5):
        """代扣轮询：**同 requestId 幂等重放**即查询（不新建扣款单），至 SUCCESS/FAILED/TERMINATED。"""
        return poll_until(lambda: self.channel_agent_pay(sign_no, amount, request_id),
                          lambda r: isinstance(r, dict) and r.get("status") in TERMINAL_DEDUCT_STATUS,
                          timeout=timeout, interval=interval)

    # ---------- 通用调用（签名 + 幂等重试 + 错误码映射 + 版本路由） ----------

    def call(self, api, **params):
        """通用调用：api=方法名（如 trade.pay）；params 为业务入参（可含 method=BALANCE）。"""
        body = dict(params)
        body["appId"] = self.app_id
        path = f"/openapi/v2/{api}" if self.api_version == "v2" and api in V2_METHODS else f"/openapi/{api}"
        attempt = 0
        while True:
            # 幂等重试：bizNo/outTradeNo/requestId 原样不变，服务端幂等键吸收重复
            signed = sign_params(body, self.app_secret,
                                 nonce=uuid.uuid4().hex if self.use_nonce else None)
            headers = {"X-Api-Version": self.api_version}
            if self.access_token:
                headers["X-Access-Token"] = self.access_token
            if self.sandbox:
                headers["X-Sandbox"] = "true"
            try:
                status, data, resp_headers = self._http("POST", path, signed, headers)
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                if attempt < self.retries:
                    attempt += 1
                    time.sleep(0.3 * attempt)
                    continue
                raise OpenApiError("C0001", f"网络异常: {e}") from e
            self.last_headers = resp_headers
            if status == 200:
                if isinstance(data, dict) and data.get("apiVersion") == "v2" and "data" in data:
                    return data["data"]
                return data
            err = OpenApiError(data.get("code", "B0002") if isinstance(data, dict) else "B0002",
                               data.get("error", str(data)) if isinstance(data, dict) else str(data),
                               status, data.get("traceId") if isinstance(data, dict) else None)
            if err.retryable and attempt < self.retries:
                attempt += 1
                time.sleep(0.3 * attempt)
                continue
            raise err

    def _http(self, method, path, body, headers=None):
        payload = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=payload, method=method)
        request.add_header("Accept", "application/json")
        if payload is not None:
            request.add_header("Content-Type", "application/json")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as resp:
                return resp.status, _parse(resp.read().decode()), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, _parse(e.read().decode()), dict(e.headers or {})


def _parse(text):
    if not text:
        return None
    try:
        return json.loads(text)
    except Exception:
        return text


def poll_until(fn, pred, timeout=30, interval=0.5):
    """多端统一轮询：只读回查至终态；超时返回最后一次结果（由调用方断言，绝不静默成功）。"""
    deadline = time.time() + timeout
    value = fn()
    while time.time() < deadline and not pred(value):
        time.sleep(interval)
        value = fn()
    return value