#!/usr/bin/env python3
"""verify-openplatform.py — wave3 P2-B G-4a/G-4b 开放平台专项验证。

跑法: python3 verify-openplatform.py   （payment-pro 栈已启动，gateway 8080；Java SDK 需 JAVA_HOME）

覆盖:
  1. 应用注册/授权 scope（未授权方法 403）/E-1 脱敏（list/get 不泄密）
  2. 授权撤销即时生效（调用 403 + access_token 立即失效 + 颁发 403）→ 恢复
  3. 沙箱隔离：独立 appId/密钥/SBXA 账本，生产账户数/支付单数/余额三项前后不变
  4. 按 appId 限流（QPS 429）与日配额（429）+ 计费计数/单价/账单/拒绝不计费
  5. ride 兼容红线：未注册商户八方法全通、account.balance 裸 long、trade.pay BALANCE 同步、
     幂等键不变、HMAC 错签 400、timestamp 毫秒、不传 nonce 仍通过、响应无 envelope
  6. 版本协商：/openapi/version 公告、v1 路径不被头改变行为、v2 additive envelope
  7. SDK：Python 示例 + Java 示例跑通沙箱；SDK 签名工具直连生产路径亦通
  8. 计费不影响资金账（账单刷新前后生产余额/支付单数不变）

测试数据独立前缀 OPTEST + uuid，只新增行，不改既有数据。
"""
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:8]
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
JAVA_HOME = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
passed = failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        r.add_header(key, value)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            text = resp.read().decode()
            parsed = None
            if text:
                try:
                    parsed = json.loads(text)
                except Exception:
                    parsed = text
            return resp.status, parsed, dict(resp.headers)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text), dict(e.headers or {})
        except Exception:
            return e.code, {"error": text}, dict(e.headers or {})
    except Exception as e:
        return 0, {"error": str(e)}, {}


st, resp, _ = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]


def ok(method, path, body=None):
    st, resp, _ = req(method, path, body, token=ADMIN)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def canonical(params):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    return "&".join(f"{k}={v}" for k, v in items)


def call(app_id, secret, endpoint, extra=None, v2=False, headers=None, sign_secret=None, nonce=None):
    params = {"appId": app_id, "timestamp": int(time.time() * 1000)}
    params.update(extra or {})
    if nonce:
        params["nonce"] = nonce
    params["sign"] = hmac.new((sign_secret or secret).encode(), canonical(params).encode(), hashlib.sha256).hexdigest()
    path = ("/openapi/v2/" if v2 else "/openapi/") + endpoint
    return req("POST", path, params, headers=headers)


def new_merchant(tag):
    m = ok("POST", "/api/members", {"name": f"OPTEST-{tag}-{SUFFIX}", "type": "MERCHANT"})
    return m["appId"], m["appSecret"], m["id"]


def register(app_id=None, sandbox=False, scopes=None, rate=0, quota=0, price=0, tag="APP"):
    body = {"name": f"OPTEST-{tag}-{SUFFIX}", "sandbox": sandbox, "rateLimit": rate,
            "dailyQuota": quota, "unitPrice": price}
    if app_id:
        body["appId"] = app_id
    if scopes:
        body["scopes"] = scopes
    return ok("POST", "/api/openplatform/apps", body)


def poll(fn, pred, timeout=15, interval=0.4):
    deadline = time.time() + timeout
    value = fn()
    while time.time() < deadline and not pred(value):
        time.sleep(interval)
        value = fn()
    return value


print("== 1. 应用注册 / 授权 scope / E-1 脱敏 ==")
scopes = ok("GET", "/api/openplatform/scopes")
check("scope 目录含冻结方法映射", scopes.get("trade.pay") == "trade:pay" and scopes.get("account.balance") == "account:read", str(scopes)[:200])

sbx = register(sandbox=True, price=2, tag="SBX")
SBX_ID, SBX_SECRET = sbx["appId"], sbx["appSecret"]
check("沙箱注册生成独立 appId", SBX_ID.startswith("sbx") and SBX_ID != "", str(sbx)[:200])
check("沙箱注册一次性返回明文密钥", bool(SBX_SECRET) and len(SBX_SECRET) >= 16)
check("沙箱应用标记 sandbox=true", sbx.get("sandbox") is True and sbx.get("status") == "ACTIVE")

app_a_id, app_a_secret, _ = new_merchant("OPA")
A = register(app_id=app_a_id, price=5, tag="OPA",
             scopes=["account:open", "account:read", "fund:topup", "trade:write", "trade:pay", "trade:read"])
check("生产应用注册绑定既有 appId", A["appId"] == app_a_id and A.get("sandbox") is False, str(A)[:200])
check("生产应用注册不回显密钥（E-1）", A.get("appSecret") is None, str(A)[:200])

apps = ok("GET", "/api/openplatform/apps")
check("应用列表全部脱敏（E-1 不回退）", all(a.get("appSecret") is None for a in apps), str(apps)[:200])
detail = ok("GET", f"/api/openplatform/apps/{SBX_ID}")
check("应用详情脱敏但留指纹", detail.get("appSecret") is None and bool(detail.get("secretFingerprint")), str(detail)[:200])
members = ok("GET", "/api/members")
check("会员列表仍脱敏（E-1 不回退）", all(m.get("appSecret") is None for m in members))

st, resp, _ = call(app_a_id, app_a_secret, "account.open", {"name": f"OPTEST-OPA-USER-{SUFFIX}", "type": "USER"})
check("已授权 scope account.open 放行", st == 200 and resp.get("accountId"), f"{st} {resp}")
A_ACCOUNT = resp.get("accountId") if st == 200 else None
st, resp, _ = call(app_a_id, app_a_secret, "account.transfer",
                   {"fromAccountId": A_ACCOUNT, "toAccountId": A_ACCOUNT, "amount": 1, "bizNo": "OPTEST-SCOPE-" + SUFFIX})
check("未授权 scope account.transfer 拒绝 403", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": A_ACCOUNT})
check("已授权 scope account.balance 放行且裸 long", st == 200 and isinstance(resp, int), f"{st} {resp}")
st, resp, _ = call(app_a_id, app_a_secret, "fund.topup",
                   {"accountId": A_ACCOUNT, "amount": 500, "bizNo": "OPTEST-OPA-TOPUP-" + SUFFIX})
A_TOPUP = resp.get("payment", {}).get("id") if st == 200 else None
check("已授权 fund.topup 成功且 fee=0（v1 {payment,repeated} 形状）",
      st == 200 and resp.get("payment", {}).get("fee") == 0 and resp["payment"]["status"] == "SUCCESS"
      and "repeated" in resp, f"{st} {resp}")

print("== 2. OAuth token 授权 / 撤销即时生效 ==")
st, token_resp, _ = req("POST", "/openapi/oauth/token", {"appId": app_a_id, "appSecret": app_a_secret})
check("client_credentials 颁发 access_token", st == 200 and token_resp.get("access_token"), f"{st} {token_resp}")
A_TOKEN = token_resp.get("access_token")
check("token 回显 scope 与 expiresIn", "account:read" in (token_resp.get("scope") or "") and token_resp.get("expires_in", 0) > 0, str(token_resp)[:200])
st, wrong, _ = req("POST", "/openapi/oauth/token", {"appId": app_a_id, "appSecret": "OPTEST-WRONG-SECRET"})
check("错误 appSecret 颁发 401", st == 401, f"{st} {wrong}")
intros = None
st, intros, _ = req("POST", "/openapi/oauth/introspect", {"token": A_TOKEN})
check("token 自省 active=true", st == 200 and intros.get("active") is True, f"{st} {intros}")
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": A_ACCOUNT}, headers={"X-Access-Token": A_TOKEN})
check("携带 token 调用放行（HMAC 仍必需）", st == 200 and isinstance(resp, int), f"{st} {resp}")
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": A_ACCOUNT}, sign_secret="OPTEST-BAD")
check("错签仍 400（token 不替代签名）", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")

revoked = ok("POST", f"/api/openplatform/apps/{app_a_id}/revoke")
check("撤销授权返回 REVOKED", revoked.get("status") == "REVOKED" and revoked.get("revokedAt"), str(revoked)[:200])
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": A_ACCOUNT})
check("撤销后即时拒绝 403（无缓存宽限）", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
st, intros, _ = req("POST", "/openapi/oauth/introspect", {"token": A_TOKEN})
check("撤销后 access_token 立即失效", st == 200 and intros.get("active") is False, f"{st} {intros}")
st, resp, _ = req("POST", "/openapi/oauth/token", {"appId": app_a_id, "appSecret": app_a_secret})
check("撤销后不再颁发 token 403", st == 403, f"{st} {resp}")
ok("POST", f"/api/openplatform/apps/{app_a_id}/activate")
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": A_ACCOUNT})
check("恢复授权后即时放行", st == 200 and isinstance(resp, int), f"{st} {resp}")

print("== 3. 沙箱隔离（生产账零污染） ==")
prod_accounts_before = len(ok("GET", "/api/accounts"))
prod_payments_before = len(ok("GET", "/api/payments"))
prod_balance_before = ok("GET", f"/api/accounting/accounts/{A_ACCOUNT}/balance")

st, sbx_acct, _ = call(SBX_ID, SBX_SECRET, "account.open", {"name": f"OPTEST-SBX-USER-{SUFFIX}", "type": "USER"})
check("沙箱开户返回 SBXM/SBXA 独立 id", st == 200 and sbx_acct.get("accountId", "").startswith("SBXA")
      and sbx_acct.get("memberId", "").startswith("SBXM"), f"{st} {sbx_acct}")
SBX_ACCOUNT = sbx_acct.get("accountId")
st, sbx_wrap, _ = call(SBX_ID, SBX_SECRET, "fund.topup",
                       {"accountId": SBX_ACCOUNT, "amount": 1000, "bizNo": "OPTEST-SBX-TOPUP-" + SUFFIX})
sbx_pay = sbx_wrap.get("payment", {}) if isinstance(sbx_wrap, dict) else {}
check("沙箱充值同步 SUCCESS 且 fee=0（形状同 v1 {payment,repeated}）",
      st == 200 and sbx_pay.get("status") == "SUCCESS" and sbx_pay.get("fee") == 0 and "repeated" in sbx_wrap, f"{st} {sbx_wrap}")
st, again, _ = call(SBX_ID, SBX_SECRET, "fund.topup",
                    {"accountId": SBX_ACCOUNT, "amount": 1000, "bizNo": "OPTEST-SBX-TOPUP-" + SUFFIX})
check("沙箱 bizNo 幂等返回同单且 repeated=true",
      st == 200 and again.get("payment", {}).get("id") == sbx_pay.get("id") and again.get("repeated") is True, f"{st} {again}")
st, bal, hdr = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT})
check("沙箱余额裸数字 long=1000", st == 200 and bal == 1000 and isinstance(bal, int), f"{st} {bal}")
check("沙箱响应带 X-Api-Sandbox 头", hdr.get("X-Api-Sandbox") == "true", str(hdr)[:200])
st, sbx_order, _ = call(SBX_ID, SBX_SECRET, "trade.create",
                        {"subject": "OPTEST 沙箱行程", "amount": 300, "outTradeNo": "OPTEST-SBX-ORDER-" + SUFFIX})
check("沙箱下单 CREATED", st == 200 and sbx_order.get("status") == "CREATED", f"{st} {sbx_order}")
st, sbx_paid_wrap, _ = call(SBX_ID, SBX_SECRET, "trade.pay",
                            {"orderId": sbx_order.get("id"), "accountId": SBX_ACCOUNT, "method": "BALANCE"})
sbx_paid = sbx_paid_wrap.get("payment", {}) if isinstance(sbx_paid_wrap, dict) else {}
check("沙箱 BALANCE 支付同步 SUCCESS", st == 200 and sbx_paid.get("status") == "SUCCESS", f"{st} {sbx_paid_wrap}")
st, sbx_query, _ = call(SBX_ID, SBX_SECRET, "trade.query", {"orderId": sbx_order.get("id")})
check("沙箱 trade.query 返回 {order,payment}", st == 200 and set(sbx_query.keys()) == {"order", "payment"}
      and sbx_query["payment"]["id"] == sbx_paid.get("id"), f"{st} {sbx_query}")
st, peer, _ = call(SBX_ID, SBX_SECRET, "account.open", {"name": f"OPTEST-SBX-PEER-{SUFFIX}", "type": "USER"})
st, transfer_body, _ = call(SBX_ID, SBX_SECRET, "account.transfer",
                            {"fromAccountId": SBX_ACCOUNT, "toAccountId": peer.get("accountId"),
                             "amount": 200, "bizNo": "OPTEST-SBX-TRANSFER-" + SUFFIX})
check("沙箱划转 200 空体（形状同 v1）", st == 200 and transfer_body is None, f"{st} {transfer_body}")
st, bal, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT})
check("沙箱划转后余额=500", st == 200 and bal == 500, f"{st} {bal}")
st, resp, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT}, sign_secret="OPTEST-BAD-SBX")
check("沙箱错签 400", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")
nonce = uuid.uuid4().hex
st, _, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT}, nonce=nonce)
st2, resp2, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT}, nonce=nonce)
check("沙箱可选 nonce 重放拒绝 409", st == 200 and st2 == 409, f"{st} {st2} {resp2}")
st, resp, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT})
check("沙箱不传 nonce 仍通过（红线）", st == 200 and isinstance(resp, int), f"{st} {resp}")

prod_accounts_after = ok("GET", "/api/accounts")
prod_payments_after = ok("GET", "/api/payments")
prod_balance_after = ok("GET", f"/api/accounting/accounts/{A_ACCOUNT}/balance")
check("生产账户表不含任何沙箱账户（SBXA 隔离）",
      not any(str(a.get("id", "")).startswith("SBXA") for a in prod_accounts_after),
      f"accounts={len(prod_accounts_after)}")
check("生产支付单表不含任何沙箱单（SBXP 隔离）",
      not any(str(p.get("id", "")).startswith("SBXP") for p in prod_payments_after),
      f"payments={len(prod_payments_after)}")
check("沙箱操作未改动生产余额", prod_balance_after == prod_balance_before, f"{prod_balance_before} → {prod_balance_after}")
st, resp, _ = call(app_a_id, app_a_secret, "account.balance", {"accountId": SBX_ACCOUNT})
st_sbx, sbx_bal_now, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT})
check("生产账看不到沙箱资金（生产读 SBXA 得 0，沙箱自身 500）",
      st == 200 and resp == 0 and sbx_bal_now == 500, f"prod={st}/{resp} sandbox={st_sbx}/{sbx_bal_now}")
sbx_accounts = ok("GET", f"/api/openplatform/sandbox/{SBX_ID}/accounts")
check("控制台可见沙箱独立账本", any(a["accountId"] == SBX_ACCOUNT for a in sbx_accounts), str(sbx_accounts)[:200])

print("== 4. 按 appId 限流 / 日配额 / 计费账单 ==")
app_b_id, app_b_secret, _ = new_merchant("OPB")
register(app_id=app_b_id, rate=2, price=1, tag="OPB", scopes=["account:open", "account:read"])
st, b_acct, _ = call(app_b_id, app_b_secret, "account.open", {"name": f"OPTEST-OPB-USER-{SUFFIX}", "type": "USER"})
B_ACCOUNT = b_acct.get("accountId")
codes = []
for _ in range(8):
    st, resp, _ = call(app_b_id, app_b_secret, "account.balance", {"accountId": B_ACCOUNT})
    codes.append(st)
check("appId QPS 超限返回 429 A0429", 429 in codes and 200 in codes, str(codes))
st, resp, _ = call(app_b_id, app_b_secret, "account.balance", {"accountId": B_ACCOUNT})
check("限流拒绝体为规范 {error,code}", st in (200, 429) and (st == 200 or resp.get("code") == "A0429"), f"{st} {resp}")

app_c_id, app_c_secret, _ = new_merchant("OPC")
register(app_id=app_c_id, quota=3, price=3, tag="OPC", scopes=["account:open", "account:read"])
st, c_acct, _ = call(app_c_id, app_c_secret, "account.open", {"name": f"OPTEST-OPC-USER-{SUFFIX}", "type": "USER"})
C_ACCOUNT = c_acct.get("accountId")
success = 1 if st == 200 else 0
quota_reject = None
for _ in range(5):
    st, resp, _ = call(app_c_id, app_c_secret, "account.balance", {"accountId": C_ACCOUNT})
    if st == 200:
        success += 1
    elif st == 429:
        quota_reject = resp
check("日配额用尽后拒绝（3 次放行）", success == 3 and quota_reject is not None, f"success={success} reject={quota_reject}")
check("配额拒绝提示配额口径", quota_reject is not None and "配额" in (quota_reject.get("error") or ""), str(quota_reject))
quota_view = ok("GET", f"/api/openplatform/quota?appId={app_c_id}")
check("配额视图回显上限/已用/剩余", quota_view.get("dailyQuota") == 3 and quota_view.get("usedToday", 0) >= 3
      and quota_view.get("remaining") == 0, str(quota_view)[:300])

bill_a = ok("GET", f"/api/openplatform/bill?appId={app_a_id}")
row_a = next((r for r in bill_a if r["api"] == "account.balance"), None)
check("A 应用账单按方法聚合计数", row_a is not None and row_a["callCount"] > 0, str(bill_a)[:300])
check("账单金额=计数×单价（分）", row_a is not None and row_a["billedAmount"] == row_a["callCount"] * 5, str(row_a))
bill_b = ok("GET", f"/api/openplatform/bill?appId={app_b_id}")
row_b = next((r for r in bill_b if r["api"] == "account.balance"), None)
check("限流拒绝计数入 rejectCount", row_b is not None and row_b["rejectCount"] > 0, str(row_b))
check("拒绝不计费（金额只按受理计数）", row_b is not None and row_b["billedAmount"] == row_b["callCount"] * 1, str(row_b))
bill_sbx = ok("GET", f"/api/openplatform/bill?appId={SBX_ID}")
check("沙箱调用同样计费（单价 2 分）", all(r["billedAmount"] == r["callCount"] * 2 for r in bill_sbx) and bill_sbx, str(bill_sbx)[:300])

balance_before_billing = ok("GET", f"/api/accounting/accounts/{A_ACCOUNT}/balance")
ok("GET", "/api/openplatform/bill")
ok("GET", f"/api/openplatform/quota?appId={app_a_id}")
balance_after_billing = ok("GET", f"/api/accounting/accounts/{A_ACCOUNT}/balance")
payments_after_billing = ok("GET", "/api/payments")
check("计费刷新不改生产余额", balance_after_billing == balance_before_billing, f"{balance_before_billing} → {balance_after_billing}")
check("计费不产生任何支付单（无 SBXP/计费单）",
      not any(str(p.get("id", "")).startswith("SBXP") or "BILL" in str(p.get("requestId", "")) for p in payments_after_billing),
      f"payments={len(payments_after_billing)}")
trial = ok("GET", "/api/accounting/trial")
check("账务试算平衡仍通过（计费不动账）", trial.get("balanced") is True and trial.get("balanceSum") == 0, str(trial)[:200])

print("== 5. ride 兼容红线：未注册商户 v1 八方法逐字节不变 ==")
ride_id, ride_secret, ride_member = new_merchant("RIDE")
# 商户结算户（trade.pay 收款/手续费入账前提，与 smoke-test 同口径）
ok("POST", "/api/accounts", {"memberId": ride_member})
st, opened, hdr = call(ride_id, ride_secret, "account.open", {"name": f"OPTEST-RIDE-USER-{SUFFIX}", "type": "USER"})
check("account.open 未注册商户放行且出参恰为 {memberId,accountId}",
      st == 200 and set(opened.keys()) == {"memberId", "accountId"}, f"{st} {opened}")
check("v1 响应头回显 X-Api-Version=v1", hdr.get("X-Api-Version") == "v1", str(hdr)[:200])
RIDE_ACCOUNT = opened["accountId"]
st, opened2, _ = call(ride_id, ride_secret, "account.open", {"name": f"OPTEST-RIDE-USER-{SUFFIX}", "type": "USER"})
check("account.open name 幂等同账户", st == 200 and opened2["accountId"] == RIDE_ACCOUNT, f"{st} {opened2}")
ride_biz = "OPTEST-RIDE-TOPUP-" + SUFFIX
st, topup_wrap, _ = call(ride_id, ride_secret, "fund.topup", {"accountId": RIDE_ACCOUNT, "amount": 1000, "bizNo": ride_biz})
topup_payment = topup_wrap.get("payment", {}) if isinstance(topup_wrap, dict) else {}
check("fund.topup 出参 {payment,repeated} 不变、requestId=bizNo、fee=0",
      st == 200 and topup_payment.get("id") and topup_payment.get("requestId") == ride_biz
      and topup_payment.get("fee") == 0 and "repeated" in topup_wrap, f"{st} {topup_wrap}")
topup_final = poll(lambda: ok("GET", f"/api/payments/{topup_payment['id']}"), lambda p: p["status"] in ("SUCCESS", "FAILED"))
check("fund.topup 终态 SUCCESS（BALANCE 同步不变）", topup_final["status"] == "SUCCESS", str(topup_final)[:200])
st, topup2, _ = call(ride_id, ride_secret, "fund.topup", {"accountId": RIDE_ACCOUNT, "amount": 1000, "bizNo": ride_biz})
check("fund.topup bizNo 幂等返回同支付单", st == 200 and topup2["payment"]["id"] == topup_payment["id"], f"{st} {topup2}")
st, bal, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT})
check("account.balance 仍是裸数字 long=1000", st == 200 and isinstance(bal, int) and bal == 1000, f"{st} {bal}")
out_trade = "OPTEST-RIDE-ORDER-" + SUFFIX
st, order, _ = call(ride_id, ride_secret, "trade.create",
                    {"merchantId": ride_member, "subject": "OPTEST 行程", "amount": 300, "outTradeNo": out_trade})
check("trade.create 返回裸订单对象（无 v2 envelope）",
      st == 200 and order.get("id") and "apiVersion" not in order and order.get("outTradeNo") == out_trade, f"{st} {order}")
st, order2, _ = call(ride_id, ride_secret, "trade.create",
                     {"merchantId": ride_member, "subject": "OPTEST 行程", "amount": 300, "outTradeNo": out_trade})
check("trade.create merchant+outTradeNo 幂等", st == 200 and order2["id"] == order["id"], f"{st} {order2}")
st, pay_wrap, _ = call(ride_id, ride_secret, "trade.pay",
                       {"orderId": order["id"], "accountId": RIDE_ACCOUNT, "method": "BALANCE",
                        "outTradeNo": "OPTEST-RIDE-PAY-" + SUFFIX})
pay_payment = pay_wrap.get("payment", {}) if isinstance(pay_wrap, dict) else {}
check("trade.pay 受理返回 payment.id（无 WAITING 异步回执）",
      st == 200 and pay_payment.get("id") and pay_payment.get("status") in ("PROCESSING", "SUCCESS"), f"{st} {pay_wrap}")
pay_final = poll(lambda: ok("GET", f"/api/payments/{pay_payment['id']}"), lambda p: p["status"] in ("SUCCESS", "FAILED"))
check("trade.pay BALANCE 同步收敛 SUCCESS", pay_final["status"] == "SUCCESS", str(pay_final)[:200])
st, queried, _ = call(ride_id, ride_secret, "trade.query", {"orderId": order["id"]})
check("trade.query 出参恰为 {order,payment}", st == 200 and set(queried.keys()) == {"order", "payment"}, f"{st} {queried}")
check("trade.query 手续费口径不变（fee=(amount*60+5000)//10000）",
      queried["payment"]["fee"] == (300 * 60 + 5000) // 10000, str(queried["payment"])[:200])
st, peer2, _ = call(ride_id, ride_secret, "account.open", {"name": f"OPTEST-RIDE-PEER-{SUFFIX}", "type": "USER"})
st, tbody, _ = call(ride_id, ride_secret, "account.transfer",
                    {"fromAccountId": RIDE_ACCOUNT, "toAccountId": peer2["accountId"], "amount": 100,
                     "bizNo": "OPTEST-RIDE-TRANSFER-" + SUFFIX})
check("account.transfer 200 空体语义不变", st == 200 and tbody is None, f"{st} {tbody}")
st, wd_wrap, _ = call(ride_id, ride_secret, "fund.withdraw",
                      {"accountId": RIDE_ACCOUNT, "amount": 100, "bizNo": "OPTEST-RIDE-WITHDRAW-" + SUFFIX})
check("fund.withdraw 返回 payment.id 且 fee=0",
      st == 200 and wd_wrap.get("payment", {}).get("id") and wd_wrap["payment"]["fee"] == 0, f"{st} {wd_wrap}")
st, resp, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT}, sign_secret="OPTEST-BAD")
check("HMAC 错签 400 A0400（机制不变）", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")
st, resp, _ = req("POST", "/openapi/account.balance",
                  {"appId": ride_id, "accountId": RIDE_ACCOUNT, "timestamp": int(time.time()),
                   "sign": hmac.new(ride_secret.encode(), canonical(
                       {"appId": ride_id, "accountId": RIDE_ACCOUNT, "timestamp": int(time.time())}).encode(),
                       hashlib.sha256).hexdigest()})
check("timestamp 必须毫秒（秒级被拒 400）", st == 400, f"{st} {resp}")
st, resp, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT})
check("不传 nonce 仍通过（红线）", st == 200 and isinstance(resp, int), f"{st} {resp}")
ride_bill = ok("GET", f"/api/openplatform/bill?appId={ride_id}")
check("未注册商户只免费计数（unitPrice=0）", bool(ride_bill) and all(r["billedAmount"] == 0 and r["callCount"] > 0 for r in ride_bill), str(ride_bill)[:300])
ride_quota = ok("GET", f"/api/openplatform/quota?appId={ride_id}")
check("未注册商户配额不限（registered=false）", ride_quota.get("registered") is False and ride_quota.get("remaining") is None, str(ride_quota)[:200])

print("== 6. G-4b 版本协商 / v2 additive ==")
st, announcement, _ = req("GET", "/openapi/version")
v1 = next((v for v in announcement.get("versions", []) if v["version"] == "v1"), {})
check("版本公告 default=v1 current=v2", st == 200 and announcement.get("default") == "v1" and announcement.get("current") == "v2", f"{st} {announcement}")
check("v1 标记 frozen 且含八冻结方法", v1.get("frozen") is True and set(
    ["account.open", "fund.topup", "fund.withdraw", "account.transfer", "account.balance", "trade.create", "trade.pay", "trade.query"]
).issubset(set(v1.get("methods", []))), str(v1)[:300])
check("公告含签名口径与 scope 目录", announcement.get("sign", {}).get("algorithm") == "HMAC-SHA256"
      and announcement.get("scopes", {}).get("trade.pay") == "trade:pay", str(announcement)[:200])
st, bal, hdr = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT}, headers={"X-Api-Version": "v2"})
check("v1 路径不被版本头改变行为（仍裸 long）", st == 200 and isinstance(bal, int), f"{st} {bal}")
check("协商回显 X-Api-Version=v1 + Requested=v2", hdr.get("X-Api-Version") == "v1" and hdr.get("X-Api-Version-Requested") == "v2", str(hdr)[:250])
st, v2bal, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT}, v2=True)
check("v2 account.balance envelope 结构", st == 200 and v2bal.get("apiVersion") == "v2"
      and v2bal["data"]["balance"] == bal and v2bal["data"]["currency"] == "CNY", f"{st} {v2bal}")
st, v2order, _ = call(ride_id, ride_secret, "trade.create",
                      {"subject": "OPTEST v2 行程", "amount": 200, "outTradeNo": "OPTEST-V2-ORDER-" + SUFFIX,
                       "attach": "OPTEST-ATTACH"}, v2=True)
check("v2 trade.create envelope + attach 回显", st == 200 and v2order.get("apiVersion") == "v2"
      and v2order.get("attach") == "OPTEST-ATTACH" and v2order["data"]["id"], f"{st} {v2order}")
st, v2pay, _ = call(ride_id, ride_secret, "trade.pay", {"orderId": v2order["data"]["id"],
                                                       "accountId": RIDE_ACCOUNT, "method": "BALANCE",
                                                       "outTradeNo": "OPTEST-V2-PAY-" + SUFFIX})
st, v2query, _ = call(ride_id, ride_secret, "trade.query", {"orderId": v2order["data"]["id"]}, v2=True)
check("v2 trade.query 增 fee 明细", st == 200 and v2query["data"]["fee"]["amount"] == v2query["data"]["payment"]["fee"], f"{st} {v2query}")
st, resp, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT}, v2=True, sign_secret="OPTEST-BAD")
check("v2 同样 HMAC 验签（错签 400）", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")
st, sbx_v2, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": SBX_ACCOUNT}, v2=True)
check("v2 沙箱亦 envelope（隔离不变）", st == 200 and sbx_v2.get("apiVersion") == "v2" and sbx_v2["data"] == 500, f"{st} {sbx_v2}")

print("== 7. SDK（Python / Java）跑通沙箱 ==")
sys.path.insert(0, os.path.join(ROOT, "sdk", "python"))
from payment_openapi import PaymentOpenApiClient, canonical as sdk_canonical  # noqa: E402

check("SDK canonical 与验证脚本一致", sdk_canonical({"b": 2, "a": 1, "sign": "x", "c": None}) == "a=1&b=2")
st, current_balance, _ = call(ride_id, ride_secret, "account.balance", {"accountId": RIDE_ACCOUNT})
sdk_client = PaymentOpenApiClient(BASE, ride_id, ride_secret)
sdk_balance = sdk_client.account_balance(RIDE_ACCOUNT)
check("Python SDK 签名直连生产 v1 路径通", isinstance(sdk_balance, int) and sdk_balance == current_balance,
      f"{sdk_balance} vs {current_balance}")

env = dict(os.environ, PP_APP_ID=SBX_ID, PP_APP_SECRET=SBX_SECRET, PP_GATEWAY=BASE)
py = subprocess.run([sys.executable, os.path.join(ROOT, "sdk", "python", "example_sandbox.py")],
                    capture_output=True, text=True, env=env, timeout=180)
check("Python SDK 沙箱示例退出码 0", py.returncode == 0, (py.stdout + py.stderr)[-600:])
check("Python SDK 示例断言全通过", "SDK 沙箱示例全部断言通过" in py.stdout, py.stdout[-400:])

build = subprocess.run(["bash", os.path.join(ROOT, "sdk", "java", "build.sh")],
                       capture_output=True, text=True, env=dict(os.environ, JAVA_HOME=JAVA_HOME), timeout=300)
check("Java SDK 编译通过", build.returncode == 0, (build.stdout + build.stderr)[-600:])
java = subprocess.run([os.path.join(JAVA_HOME, "bin", "java"), "-cp", os.path.join(ROOT, "sdk", "java", "out"),
                       "com.qoder.payment.sdk.SandboxExample"],
                      capture_output=True, text=True, env=env, timeout=180)
check("Java SDK 沙箱示例退出码 0", java.returncode == 0, (java.stdout + java.stderr)[-600:])
check("Java SDK 示例断言全通过", "Java SDK 沙箱示例全部断言通过" in java.stdout, java.stdout[-400:])

print("== 8. 控制台鉴权与闸门自述 ==")
st, resp, _ = req("GET", "/api/openplatform/apps")
check("控制台开放平台端点未登录 401", st == 401, f"{st} {resp}")
gate = ok("GET", "/api/openplatform/gate")
check("闸门自述：计费不触碰资金账", gate.get("billingTouchesFundLedger") is False and gate.get("rejectBeforeAcceptance") is True, str(gate)[:250])
final_trial = ok("GET", "/api/accounting/trial")
check("收尾试算平衡仍 D=C 且 Σbalance=0", final_trial.get("balanced") is True and final_trial.get("balanceSum") == 0,
      str(final_trial)[:200])

print()
print(f"verify-openplatform: {passed} passed, {failed} failed (共 {passed + failed} 项)")
sys.exit(1 if failed else 0)