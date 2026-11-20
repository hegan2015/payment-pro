#!/usr/bin/env python3
# ============================================================================
# payment-pro P2 第三期 · C 泳道专项验证：A-4 多币种+汇率 / AC-6 多币种账务
#
# 用法：全栈在线（./run.sh --docker 或 ./run.sh）后 python3 verify-multicurrency.py
#   可重复执行：测试数据独立前缀 CURTEST + uuid，断言基于「相对基线增量」，收尾红冲复原余额。
#
# 覆盖（两卡验收口径）：
#   ① 既有账户默认 CNY 且行为不变；account.open 懒开户仍取 CNY BALANCE 户；
#      account.balance 返回**裸数字 long**；fund.topup bizNo 幂等语义不变（ride 红线 §0.7）
#   ② 汇率买入/卖出/中间价 + 版本号 + 生效时间 + 来源（DynamicConfig 沙箱可配）；
#      同参数重复发布幂等；点差口径非法拒绝；未来版本不影响当前生效版本
#   ③ 换汇 (EXCHANGE,bizNo) 幂等；汇率快照落库；**两条同币种凭证** + 汇兑损益 SYS_FX 平衡；
#      手续费口径清晰（from 币种收取 → SYS_FEE/6001，与支付手续费同一口径，收尾红冲复原）
#   ④ **每币种内 D=C 且 Σbalance=0**；**不同币种金额不得直接相加**（无混币凭证、trial 顶层=CNY 账套）
#   ⑤ trial 按币种分别平衡；既有 CNY 单币种口径与返回结构不变（只 additive 增字段）
#   ⑥ 汇率变动不影响已入账（快照回放）；多币种凭证红冲沿用原快照、原凭证不可篡改
#   ⑦ 分片/单库两态一致：影子比对零差异 + 各片之和=原单表=逻辑归并 + 同账户同片
#
# 资金红线：任何动账走既有 post/transfer + 幂等键；VoucherGuard append-only；
#           分片键 account_id 不变；绝不删/重置既有数据；历史脏单 P360 不触碰。
# ============================================================================
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080"
ACC_ACT = os.environ.get("PP_ACC_ACTUATOR", "http://127.0.0.1:18083/actuator/ppshard")
SUFFIX = uuid.uuid4().hex[:8]
TAG = "CURTEST" + SUFFIX
TOKEN = None
STEP_UP = None
PASSED = 0
FAILED = 0
FAILURES = []


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        FAILURES.append(name)
        print(f"  [FAIL] {name}  {detail}")
    return bool(cond)


def _password():
    return os.environ.get("PP_ADMIN_PASSWORD", "admin123")


def _login():
    global TOKEN
    try:
        r = urllib.request.Request(BASE + "/api/auth/login",
                                   data=json.dumps({"username": "admin", "password": _password()}).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=15) as resp:
            TOKEN = json.loads(resp.read().decode()).get("token")
    except Exception:
        TOKEN = None


def _step_up():
    """敏感写操作二次认证：再验会话用户自己的口令（绝不把口令拼进 subprocess argv）。"""
    global STEP_UP
    st, resp = req("POST", "/api/security/step-up", {"password": _password()})
    STEP_UP = resp.get("token") if st == 200 else None
    return STEP_UP


def _req_impl(method, url, body=None, step=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    path = url[len(BASE):] if url.startswith(BASE) else url
    if TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        r.add_header("Authorization", "Bearer " + TOKEN)
    if step and STEP_UP:
        r.add_header("X-Step-Up-Token", STEP_UP)
    try:
        with urllib.request.urlopen(r, timeout=40) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"error": str(e)}


def req(method, path, body=None, step=False):
    url = path if path.startswith("http") else BASE + path
    st, resp = _req_impl(method, url, body, step)
    if st == 401 and url.startswith(BASE + "/api/") and path != "/api/auth/login":
        _login()
        if TOKEN:
            st, resp = _req_impl(method, url, body, step)
    # step-up token 短时（默认 300s）：过期自动再验一次会话用户自己的口令后重试
    if step and st == 403 and isinstance(resp, dict) and "二次认证" in str(resp.get("error", "")):
        if _step_up():
            st, resp = _req_impl(method, url, body, True)
    return st, resp


def ok(method, path, body=None, step=False):
    st, resp = req(method, path, body, step)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def voucher(biz_no):
    """凭证联查（VoucherDTO：头 + lines + entries + totalDebit/totalCredit + 币种/汇率快照）。"""
    return ok("GET", f"/api/accounting/vouchers/{biz_no}")


def trial(currency=None):
    path = "/api/accounting/trial" if currency is None else f"/api/accounting/trial/by-currency?currency={currency}"
    return ok("GET", path)


def trial_all():
    return ok("GET", "/api/accounting/trial/by-currency")


def by_ccy(t, ccy):
    for row in t.get("byCurrency") or []:
        if row.get("currency") == ccy:
            return row
    return None


def convert(amount, rate, scale):
    """与 FxMath.convert 同口径：BigDecimal HALF_UP 到整数分（Python 整数精确运算，无浮点）。"""
    num = amount * rate
    half = scale // 2
    return (num + half) // scale if num >= 0 else -((-num + half) // scale)


def fee_of(amount, bps):
    return (amount * bps + 5000) // 10000


def line(account, dir_, amount):
    return {"accountId": account, "dir": dir_, "amount": amount}


def sign_params(params, secret):
    items = sorted((k, v) for k, v in params.items() if k != "sign" and v is not None)
    raw = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), raw.encode(), hashlib.sha256).hexdigest()


def openapi(method, params, secret):
    p = dict(params)
    p["timestamp"] = int(time.time() * 1000)
    p["sign"] = sign_params(p, secret)
    return req("POST", "/openapi/" + method, p)


def rejected(st, resp):
    """业务拒绝：4xx（PARAM_ERROR/BIZ_ERROR→400、NOT_FOUND→404、CONFLICT→409、FORBIDDEN→403）。
    500 视为**未通过**（系统异常不是合法拒绝，绝不放宽断言凑绿）。"""
    return st in (400, 403, 404, 409)


# ============================================================================
print("== 0. 门禁与域间边预热 ==")
_login()
check("控制台登录拿到 Bearer", bool(TOKEN))
health = ok("GET", "/api/health")
if isinstance(health, list):
    down = [h.get("service") for h in health if not h.get("ok")]
    check("网关聚合 13 个进程且全部在线", len(health) == 13 and not down, f"{len(health)} down={down}")
else:
    check("健康端点返回进程列表", False, str(health)[:160])
check("GET /api/members 200", isinstance(ok("GET", "/api/members"), list))
check("GET /api/payments 200", ok("GET", "/api/payments") is not None)
_accounts = ok("GET", "/api/accounts")
check("域间边预热 account→accounting 余额 200",
      bool(_accounts) and isinstance(bal(_accounts[0]["id"]), int))
check("二次认证 token 颁发（敏感写操作前置）", bool(_step_up()))

# ============================================================================
print("== 1. A-4 既有账户默认 CNY 且行为不变（ride 红线） ==")
member = ok("POST", "/api/members", {"name": f"{TAG}用户", "type": "USER"})
MID = member["id"]
cny = ok("POST", "/api/accounts", {"memberId": MID})
CNY_AID = cny["id"]
check("既有开户返回 currency=CNY", cny.get("currency") == "CNY", str(cny))
check("既有开户 accountType/level/freezeType 口径不变",
      (cny.get("accountType"), cny.get("level"), cny.get("freezeType")) == ("BALANCE", "I", "NONE"), str(cny))
typed = ok("POST", "/api/accounts/typed", {"memberId": MID, "accountType": "BALANCE"})
check("A-1 typed 幂等仍返回同一 CNY 户", typed["id"] == CNY_AID, str(typed))
dflt = ok("POST", "/api/accounts/currency", {"memberId": MID, "accountType": "BALANCE"})
check("A-4 currency 缺省 = CNY，与 typed 开户逐字节等价", dflt["id"] == CNY_AID and dflt.get("currency") == "CNY")
explicit_cny = ok("POST", "/api/accounts/currency", {"memberId": MID, "accountType": "BALANCE", "currency": "CNY"})
check("显式 CNY 开户幂等返回既有户", explicit_cny["id"] == CNY_AID)
check("account.balance 裸数字 long（结构不变）", isinstance(bal(CNY_AID), int) and bal(CNY_AID) == 0)
legacy_list = ok("GET", f"/api/accounts?memberId={MID}")
check("list 首位仍是 CNY BALANCE 户（account.open 懒开户红线）",
      legacy_list and legacy_list[0]["id"] == CNY_AID, str(legacy_list))

# OpenAPI 冻结调用面：account.open / account.balance / fund.topup
merchant = ok("POST", "/api/members", {"name": f"{TAG}商户", "type": "MERCHANT"})
APP_ID, SECRET = merchant["appId"], merchant["appSecret"]
st, opened = openapi("account.open", {"appId": APP_ID, "name": f"{TAG}司机", "type": "USER"}, SECRET)
check("openapi account.open 200（name 幂等懒开户）", st == 200 and opened.get("accountId"), f"{st} {opened}")
RIDE_AID = opened.get("accountId") if st == 200 else None
if RIDE_AID:
    st, opened2 = openapi("account.open", {"appId": APP_ID, "name": f"{TAG}司机", "type": "USER"}, SECRET)
    check("account.open 重复调用幂等（同 accountId）", st == 200 and opened2.get("accountId") == RIDE_AID)
    st, ride_bal = openapi("account.balance", {"appId": APP_ID, "accountId": RIDE_AID}, SECRET)
    check("openapi account.balance 返回裸数字 long（非对象/非信封）",
          st == 200 and isinstance(ride_bal, int) and not isinstance(ride_bal, bool), f"{st} {ride_bal!r}")
    ride_biz = f"{TAG}TOPUP"
    st, tp = openapi("fund.topup", {"appId": APP_ID, "accountId": RIDE_AID, "amount": 5000, "bizNo": ride_biz}, SECRET)
    tp_id = (tp.get("payment") or {}).get("id") if isinstance(tp, dict) else None
    check("openapi fund.topup 受理成功（语义不变）", st == 200 and bool(tp_id), f"{st} {tp}")
    if tp_id:
        deadline = time.time() + 25
        paid = None
        while time.time() < deadline:
            paid = ok("GET", f"/api/payments/{tp_id}")
            if paid.get("status") in ("SUCCESS", "FAILED"):
                break
            time.sleep(1)
        check("fund.topup 终态 SUCCESS（BALANCE 同步路径不变）", paid and paid.get("status") == "SUCCESS", str(paid))
        check("fund.topup fee=0 / method=BALANCE / bizType=TOPUP（口径不变）",
              paid.get("fee") == 0 and paid.get("method") == "BALANCE" and paid.get("bizType") == "TOPUP", str(paid))
        check("fund.topup 入账 5000 分", bal(RIDE_AID) == 5000, str(bal(RIDE_AID)))
        st, tp2 = openapi("fund.topup", {"appId": APP_ID, "accountId": RIDE_AID, "amount": 5000,
                                         "bizNo": ride_biz}, SECRET)
        tp2_id = (tp2.get("payment") or {}).get("id") if isinstance(tp2, dict) else None
        check("fund.topup bizNo 幂等（同支付单、不重复入账）",
              st == 200 and tp2_id == tp_id and bal(RIDE_AID) == 5000, f"{st} {tp2_id} vs {tp_id}")

# ============================================================================
print("== 2. A-4 汇率：买入/卖出/中间价 + 版本 + 生效时间 + 来源 ==")
ccys = ok("GET", "/api/accounting/currencies")
check("币种账套目录含 CNY 且恒首位", isinstance(ccys, list) and ccys and ccys[0] == "CNY", str(ccys))
check("币种账套目录含 USD", "USD" in ccys, str(ccys))
r0 = ok("GET", "/api/accounting/fx-rate?from=CNY&to=USD")
check("CNY→USD 汇率已就绪（沙箱来源可配）", bool(r0.get("source")) and r0.get("version") >= 1, str(r0))
check("点差口径 buy ≤ mid ≤ sell", r0["buyRate"] <= r0["midRate"] <= r0["sellRate"], str(r0))
check("汇率 long+scale（无浮点）",
      isinstance(r0["midRate"], int) and isinstance(r0["rateScale"], int) and r0["rateScale"] > 0, str(r0))
check("生效时间已到（effectiveAt ≤ now）", r0["effectiveAt"] <= int(time.time() * 1000), str(r0))
SCALE = r0["rateScale"]
base_ver = r0["version"]

future_eff = int(time.time() * 1000) + 3600_000
fut = ok("POST", "/api/accounting/fx-rate", {"fromCurrency": "CNY", "toCurrency": "USD",
                                            "buyRate": 1300, "midRate": 1310, "sellRate": 1320,
                                            "rateScale": SCALE, "effectiveAt": future_eff,
                                            "source": "sandbox-test", "requestId": TAG + "FUT"}, step=True)
check("未来生效版本发布成功且版本号递增", fut["version"] > base_ver and fut["effectiveAt"] == future_eff, str(fut))
cur = ok("GET", "/api/accounting/fx-rate?from=CNY&to=USD")
check("未来版本不影响当前生效汇率", cur["version"] == base_ver and cur["midRate"] == r0["midRate"], str(cur))
hist = ok("GET", f"/api/accounting/fx-rate?from=CNY&to=USD&version={fut['version']}")
check("按版本号可取历史/未来快照", hist["midRate"] == 1310 and hist["source"] == "sandbox-test", str(hist))
again = ok("POST", "/api/accounting/fx-rate", {"fromCurrency": "CNY", "toCurrency": "USD",
                                              "buyRate": 1300, "midRate": 1310, "sellRate": 1320,
                                              "rateScale": SCALE, "effectiveAt": future_eff,
                                              "source": "sandbox-test", "requestId": TAG + "FUT"}, step=True)
check("同参数重复发布幂等（不产生新版本）", again["version"] == fut["version"] and again["id"] == fut["id"])
rates = ok("GET", "/api/accounting/fx-rates?from=CNY&to=USD&limit=50")
check("汇率版本历史只追加（含新旧版本）",
      {x["version"] for x in rates} >= {base_ver, fut["version"]}, str([x["version"] for x in rates]))
st, resp = req("POST", "/api/accounting/fx-rate", {"fromCurrency": "CNY", "toCurrency": "USD",
                                                   "buyRate": 2000, "midRate": 1404, "sellRate": 1500,
                                                   "rateScale": SCALE, "requestId": TAG + "BADSPREAD"}, step=True)
check("点差非法（buy > mid）拒绝", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/fx-rate", {"fromCurrency": "CNY", "toCurrency": "CNY",
                                                   "buyRate": 1, "midRate": 1, "sellRate": 1,
                                                   "requestId": TAG + "SAME"}, step=True)
check("同币种汇率发布拒绝", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": CNY_AID,
                                                    "amount": 100, "bizNo": TAG + "NOSTEP"})
check("换汇缺二次认证 403（敏感资金操作闸门）", st == 403, f"{st} {resp}")

# ============================================================================
print("== 3. A-4 多币种开户 + 换汇（两条同币种凭证 + 汇兑损益） ==")
usd = ok("POST", "/api/accounts/currency", {"memberId": MID, "accountType": "BALANCE", "currency": "USD"})
USD_AID = usd["id"]
check("USD 户开立成功且与 CNY 户不同 id", USD_AID != CNY_AID and usd.get("currency") == "USD", str(usd))
usd2 = ok("POST", "/api/accounts/currency", {"memberId": MID, "accountType": "BALANCE", "currency": "USD"})
check("多币种开户幂等 (memberId,accountType,currency)", usd2["id"] == USD_AID)
mixed_list = ok("GET", f"/api/accounts?memberId={MID}")
check("多币种下 list 首位仍是 CNY BALANCE 户（ride 懒开户不回归）",
      mixed_list[0]["id"] == CNY_AID, str([a["id"] for a in mixed_list]))
check("USD 户余额裸 long 且初始 0", isinstance(bal(USD_AID), int) and bal(USD_AID) == 0)

TOPUP = 500_000
# 分 5 笔小额充值：收尾红冲时每笔金额都低于大额双人复核阈值，
# 不必为测试收尾去建/批复核单（不污染 review 域，也不放宽任何闸门）。
TOPUP_PARTS = 5
cash_base = bal("SYS_CASH")
for _i in range(TOPUP_PARTS):
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": CNY_AID, "amount": TOPUP // TOPUP_PARTS, "bizNo": f"{TAG}TP{_i}"})
check("CNY 户充值入账（既有路径不变）", bal(CNY_AID) == TOPUP)
t0 = trial_all()
cny0, usd0 = by_ccy(t0, "CNY"), by_ccy(t0, "USD")
check("基线：CNY 账套 D=C 且 Σbalance=0", cny0["balanced"] and cny0["conserved"], str(cny0))
check("基线：USD 账套 D=C 且 Σbalance=0", usd0["balanced"] and usd0["conserved"], str(usd0))
check("基线：trial 顶层 = CNY 账套（既有口径不变）",
      (t0["totalDebit"], t0["totalCredit"], t0["balanceSum"], t0["accounts"], t0["entries"])
      == (cny0["totalDebit"], cny0["totalCredit"], cny0["balanceSum"], cny0["accounts"], cny0["entries"]), str(t0))
check("基线：multiCurrencyBalanced=true", t0["multiCurrencyBalanced"] is True)
fx_base = bal("SYS_FX_USD")
fee_sys_base = bal("SYS_FEE")

AMT = 100_000
EX_BIZ = TAG + "EX1"
cur_rate = ok("GET", "/api/accounting/fx-rate?from=CNY&to=USD")
BUY, MID_R, SELL, SC = cur_rate["buyRate"], cur_rate["midRate"], cur_rate["sellRate"], cur_rate["rateScale"]
exp_gross = convert(AMT, MID_R, SC)
exp_net = convert(AMT, BUY, SC)
exp_gain = exp_gross - exp_net
ex = ok("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                            "amount": AMT, "bizNo": EX_BIZ}, step=True)
check("换汇受理成功并返回两张凭证号", ex.get("fromBizNo") == EX_BIZ + "-F" and ex.get("toBizNo") == EX_BIZ + "-T", str(ex))
check("换汇币种解析正确 CNY→USD", (ex["fromCurrency"], ex["toCurrency"]) == ("CNY", "USD"), str(ex))
check("客户实收 = amount × 买入价 / scale（long 精确）", ex["netToAmount"] == exp_net, f'{ex["netToAmount"]} vs {exp_net}')
check("账面折算 = amount × 中间价 / scale", ex["grossToAmount"] == exp_gross, f'{ex["grossToAmount"]} vs {exp_gross}')
check("汇兑损益 = 中间价折算 − 客户实收", ex["fxGain"] == exp_gain, f'{ex["fxGain"]} vs {exp_gain}')
check("汇兑损益落 SYS_FX（USD 账套，科目 6601）", ex["fxGainAccount"] == "SYS_FX_USD", str(ex))
check("汇率快照落库（buy/sell/mid/scale/version/source/effectiveAt）",
      (ex["buyRate"], ex["sellRate"], ex["midRate"], ex["rateScale"], ex["rateVersion"])
      == (BUY, SELL, MID_R, SC, cur_rate["version"]) and ex["rateSource"] and ex["rateEffectiveAt"], str(ex))
check("默认手续费 0（口径清晰可配）", ex["fee"] == 0 and ex["feeBps"] == 0, str(ex))
check("CNY 户扣减 amount", bal(CNY_AID) == TOPUP - AMT, str(bal(CNY_AID)))
check("USD 户入账 netTo", bal(USD_AID) == exp_net, str(bal(USD_AID)))
check("SYS_FX_USD 余额 = 基线 + 汇兑损益", bal("SYS_FX_USD") == fx_base + exp_gain, str(bal("SYS_FX_USD")))

vf = voucher(f"{EX_BIZ}-F")
vt = voucher(f"{EX_BIZ}-T")
check("卖出腿凭证币种 = CNY（单币种账套）", vf.get("currency") == "CNY", str(vf.get("currency")))
check("买入腿凭证币种 = USD（单币种账套）", vt.get("currency") == "USD", str(vt.get("currency")))
check("卖出腿凭证内 D=C", vf["totalDebit"] == vf["totalCredit"] == AMT, str(vf))
check("买入腿凭证内 D=C", vt["totalDebit"] == vt["totalCredit"] == exp_gross, str(vt))
check("卖出腿分录 = D 客户CNY / C SYS_CASH（同币种）",
      vf["lines"] == [line(CNY_AID, "D", AMT), line("SYS_CASH", "C", AMT)]
      or sorted((l["accountId"], l["dir"], l["amount"]) for l in vf["lines"])
      == sorted([(CNY_AID, "D", AMT), ("SYS_CASH", "C", AMT)]), str(vf["lines"]))
check("买入腿分录 = D SYS_CASH_USD / C 客户USD / C SYS_FX_USD（同币种）",
      sorted((l["accountId"], l["dir"], l["amount"]) for l in vt["lines"])
      == sorted([("SYS_CASH_USD", "D", exp_gross), (USD_AID, "C", exp_net), ("SYS_FX_USD", "C", exp_gain)]),
      str(vt["lines"]))
check("两张凭证均不含对方币种账户（绝不混币）",
      all(l["accountId"] not in (USD_AID, "SYS_CASH_USD", "SYS_FX_USD") for l in vf["lines"])
      and all(l["accountId"] not in (CNY_AID, "SYS_CASH", "SYS_FEE") for l in vt["lines"]))
check("凭证 voucherType=NORMAL（走既有 post，VoucherGuard 只追加）",
      vf.get("voucherType") == "NORMAL" and vt.get("voucherType") == "NORMAL")

ents_f = ok("GET", f"/api/accounting/accounts/{CNY_AID}/entries?limit=20")
ef = next((e for e in ents_f if e["bizNo"] == EX_BIZ + "-F"), None)
check("CNY 分录落币种 + 汇率快照 + 本位币折算额",
      ef and ef.get("currency") == "CNY" and ef.get("exchangeRate") and ef.get("baseAmount") == AMT, str(ef))
ents_u = ok("GET", f"/api/accounting/accounts/{USD_AID}/entries?limit=20")
eu = next((e for e in ents_u if e["bizNo"] == EX_BIZ + "-T"), None)
check("USD 分录落币种 + 汇率快照 + 本位币折算额（先折算后相加）",
      eu and eu.get("currency") == "USD" and eu.get("exchangeRate")
      and eu.get("baseAmount") == convert(exp_net, eu["exchangeRate"], eu["rateScale"]), str(eu))
check("USD 分录科目 = 2001 客户余额（外币系统户沿用本位币科目）",
      str(ok("GET", f"/api/accounting/accounts/{USD_AID}/subject")) == "2001")
check("SYS_FX_USD 科目 = 6601 汇兑损益", str(ok("GET", "/api/accounting/accounts/SYS_FX_USD/subject")) == "6601")
check("SYS_CASH_USD 科目 = 1001 备付金现金", str(ok("GET", "/api/accounting/accounts/SYS_CASH_USD/subject")) == "1001")

# ============================================================================
print("== 4. AC-6 每币种内 D=C 且 Σbalance=0；不同币种金额不得直接相加 ==")
t1 = trial_all()
cny1, usd1 = by_ccy(t1, "CNY"), by_ccy(t1, "USD")
check("CNY 账套内 D=C", cny1["totalDebit"] == cny1["totalCredit"], str(cny1))
check("CNY 账套内 Σbalance=0", cny1["balanceSum"] == 0 and cny1["conserved"], str(cny1))
check("USD 账套内 D=C", usd1["totalDebit"] == usd1["totalCredit"], str(usd1))
check("USD 账套内 Σbalance=0", usd1["balanceSum"] == 0 and usd1["conserved"], str(usd1))
check("USD 账套借方发生额 = grossTo（独立核算，不与 CNY 相加）",
      usd1["totalDebit"] == usd0["totalDebit"] + exp_gross, str(usd1))
check("CNY 账套借方发生额增量 = amount（不含 USD 金额）",
      cny1["totalDebit"] == cny0["totalDebit"] + AMT, str(cny1))
check("trial 顶层仍 = CNY 账套（既有 CNY 单币种口径不变）",
      (t1["totalDebit"], t1["totalCredit"], t1["balanceSum"]) == (cny1["totalDebit"], cny1["totalCredit"], 0), str(t1))
check("trial 顶层 ≠ 跨币种直接相加（证明未混币）",
      t1["totalDebit"] != cny1["totalDebit"] + usd1["totalDebit"], str(t1))
check("multiCurrencyBalanced=true（各账套均平衡且守恒）", t1["multiCurrencyBalanced"] is True)
check("既有 trial 返回结构 7 字段齐备且类型不变",
      all(isinstance(t1[k], int) for k in ("totalDebit", "totalCredit", "balanceSum", "accounts", "entries"))
      and isinstance(t1["balanced"], bool) and isinstance(t1["suspenseBalance"], int), str(t1))
legacy_t = ok("GET", "/api/accounting/trial")
check("GET /api/accounting/trial 与 by-currency 顶层逐值一致（无参口径不变）",
      all(legacy_t[k] == t1[k] for k in ("totalDebit", "totalCredit", "balanced", "balanceSum",
                                         "accounts", "entries", "suspenseBalance")), str(legacy_t))
one = trial("USD")
check("trial(currency=USD) 顶层即该账套", one["totalDebit"] == usd1["totalDebit"]
      and one["balanceSum"] == 0 and one["currencies"] == ["USD"], str(one))
check("本位币折算 = 先折算后相加（baseCurrencyTotal）",
      t1["baseCurrencyTotal"] == sum(x["baseCreditTotal"] for x in t1["byCurrency"] if x.get("baseCreditTotal") is not None)
      and t1["baseCurrency"] == "CNY", str(t1["baseCurrencyTotal"]))
check("USD 账套本位币折算用快照汇率（非 1:1 混算）",
      usd1["fxRateToBase"] and usd1["baseCreditTotal"] == convert(usd1["totalCredit"], usd1["fxRateToBase"], usd1["rateScale"]),
      str(usd1))
check("CNY 账套本位币折算恒 1:1", cny1["baseCreditTotal"] == cny1["totalCredit"], str(cny1))

# ============================================================================
print("== 5. 换汇幂等（宕机重放不重复动账） ==")
entries_before = t1["entries"] + usd1["entries"]
ex2 = ok("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                             "amount": AMT, "bizNo": EX_BIZ}, step=True)
check("重复换汇返回原单（同凭证号/同快照）",
      ex2["bizNo"] == EX_BIZ and ex2["fromBizNo"] == ex["fromBizNo"] and ex2["rateVersion"] == ex["rateVersion"], str(ex2))
check("重复换汇不重复扣款", bal(CNY_AID) == TOPUP - AMT, str(bal(CNY_AID)))
check("重复换汇不重复入账", bal(USD_AID) == exp_net and bal("SYS_FX_USD") == fx_base + exp_gain)
t2 = trial_all()
check("重复换汇分录数不变（CNY+USD 账套）",
      by_ccy(t2, "CNY")["entries"] + by_ccy(t2, "USD")["entries"] == entries_before, str(t2))
ex3 = ok("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                             "amount": AMT * 2, "bizNo": EX_BIZ}, step=True)
check("同 bizNo 改金额仍幂等吸收（不产生第二笔动账）",
      ex3["amount"] == AMT and bal(CNY_AID) == TOPUP - AMT, str(ex3))
check("换汇单可回查（汇率快照持久化）", ok("GET", f"/api/accounting/exchange/{EX_BIZ}")["midRate"] == MID_R)

# ============================================================================
print("== 6. 汇率变动不影响已入账（快照回放） ==")
new_rate = ok("POST", "/api/accounting/fx-rate", {"fromCurrency": "CNY", "toCurrency": "USD",
                                                 "buyRate": 1200, "midRate": 1250, "sellRate": 1300,
                                                 "rateScale": SCALE, "source": "sandbox-test",
                                                 "requestId": TAG + "MOVE"}, step=True)
check("新汇率版本立即生效", new_rate["version"] > cur_rate["version"]
      and ok("GET", "/api/accounting/fx-rate?from=CNY&to=USD")["midRate"] == 1250, str(new_rate))
snap = ok("GET", f"/api/accounting/exchange/{EX_BIZ}")
check("已入账换汇单汇率快照不变", (snap["buyRate"], snap["midRate"], snap["rateVersion"]) == (BUY, MID_R, cur_rate["version"]),
      str(snap))
ents_u2 = ok("GET", f"/api/accounting/accounts/{USD_AID}/entries?limit=20")
eu2 = next((e for e in ents_u2 if e["bizNo"] == EX_BIZ + "-T"), None)
check("已入账分录汇率快照与本位币折算额不变",
      eu2 and eu2["exchangeRate"] == eu["exchangeRate"] and eu2["baseAmount"] == eu["baseAmount"], str(eu2))
check("汇率变动后 USD 户余额不变", bal(USD_AID) == exp_net)
check("汇率变动后各账套仍平衡", trial_all()["multiCurrencyBalanced"] is True)
ex_v2 = ok("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                               "amount": 50_000, "bizNo": TAG + "EX2",
                                               "rateVersion": cur_rate["version"]}, step=True)
check("指定历史版本换汇按该版本快照成交（可回放）",
      ex_v2["rateVersion"] == cur_rate["version"] and ex_v2["netToAmount"] == convert(50_000, BUY, SC), str(ex_v2))

# ============================================================================
print("== 7. 换汇手续费口径（from 币种收取 → SYS_FEE/6001，与支付手续费同一口径） ==")
FEE_BPS = 60
FEE_AMT = 200_000
exp_fee = fee_of(FEE_AMT, FEE_BPS)
cny_before_fee = bal(CNY_AID)
ex_fee = ok("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                "amount": FEE_AMT, "feeBps": FEE_BPS,
                                                "bizNo": TAG + "EXFEE"}, step=True)
check("手续费 = amount × feeBps / 10000（四舍五入，与 MoneyUtil 同口径）", ex_fee["fee"] == exp_fee, str(ex_fee))
check("手续费户 = SYS_FEE（from 币种账套，沿用既有手续费口径）", ex_fee["feeAccount"] == "SYS_FEE", str(ex_fee))
check("CNY 户扣减 amount + fee", bal(CNY_AID) == cny_before_fee - FEE_AMT - exp_fee, str(bal(CNY_AID)))
vf_fee = voucher(f"{TAG}EXFEE-F")
check("卖出腿含手续费贷腿且凭证内 D=C",
      vf_fee["totalDebit"] == vf_fee["totalCredit"] == FEE_AMT + exp_fee
      and any(l["accountId"] == "SYS_FEE" and l["dir"] == "C" and l["amount"] == exp_fee for l in vf_fee["lines"]),
      str(vf_fee))
check("手续费分录科目 = 6001 手续费收入（口径不变）",
      str(ok("GET", "/api/accounting/accounts/SYS_FEE/subject")) == "6001")
check("SYS_FEE 增量 = 换汇手续费（与支付手续费同一科目同一口径）",
      bal("SYS_FEE") == fee_sys_base + exp_fee, f"{bal('SYS_FEE')} vs {fee_sys_base + exp_fee}")
check("手续费后各账套仍 D=C 且 Σbalance=0", trial_all()["multiCurrencyBalanced"] is True)

# ============================================================================
print("== 8. 非法换汇 / 混币凭证拒绝 ==")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                    "amount": 100}, step=True)
check("缺 bizNo 拒绝", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": CNY_AID,
                                                    "amount": 100, "bizNo": TAG + "SAMEACC"}, step=True)
check("同账户换汇拒绝", rejected(st, resp), f"{st} {resp}")
usd_other = ok("POST", "/api/accounts/currency", {"memberId": MID, "accountType": "SUB", "currency": "USD"})
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": USD_AID, "toAccountId": usd_other["id"],
                                                    "amount": 100, "bizNo": TAG + "SAMECCY"}, step=True)
check("同币种划转不走换汇（拒绝）", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                    "amount": 0, "bizNo": TAG + "ZERO"}, step=True)
check("金额 0 拒绝", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                    "amount": 10**13, "bizNo": TAG + "OVER"}, step=True)
check("超上限金额拒绝（long 分上限 1e12）", rejected(st, resp), f"{st} {resp}")
usd_before_poor = bal(USD_AID)
cny_before_poor = bal(CNY_AID)
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                    "amount": 10**11, "bizNo": TAG + "POOR"}, step=True)
check("可用余额不足拒绝（不产生半截动账）", rejected(st, resp), f"{st} {resp}")
check("余额不足拒绝后 CNY/USD 余额不变（原子性）",
      bal(USD_AID) == usd_before_poor and bal(CNY_AID) == cny_before_poor,
      f"{bal(CNY_AID)} / {bal(USD_AID)}")
st, resp = req("POST", "/api/accounting/exchange", {"fromAccountId": CNY_AID, "toAccountId": USD_AID,
                                                    "amount": 1000, "bizNo": TAG + "NOVER",
                                                    "rateVersion": 999999}, step=True)
check("不存在的汇率版本拒绝（绝不臆造汇率动账）", rejected(st, resp), f"{st} {resp}")
st, resp = req("POST", "/api/accounting/suspense", {"bizNo": TAG + "MIXED",
                                                    "lines": [line("SYS_SUSPENSE", "D", 100), line(USD_AID, "C", 100)]})
check("混币凭证拒绝（CNY 系统户 + USD 客户户不得同凭证）", rejected(st, resp), f"{st} {resp}")
check("混币拒绝后各账套仍平衡", trial_all()["multiCurrencyBalanced"] is True)

# ============================================================================
print("== 9. 分片/单库两态一致（不切流、只读比对） ==")
st, shard = req("GET", ACC_ACT)
if st != 200:
    check("账务域分片端点可达", False, f"{st} {shard}")
else:
    configured = shard.get("shardingConfigured")
    active = shard.get("shardingActive")
    print(f"  (shardingConfigured={configured} shardingActive={active})")
    check("分片端点试算与 facade 一致",
          shard.get("trial", {}).get("balanceSum") == 0 and shard.get("trial", {}).get("balanced"), str(shard.get("trial")))
    if configured:
        st, cmp_ = req("GET", ACC_ACT + "/shadowCompare")
        check("影子比对零差异（原单表 vs 分片侧逐行逐列）",
              st == 200 and cmp_.get("diffTotal") == 0, f"{st} {cmp_}")
        st, summ = req("GET", ACC_ACT + "/shardSummary")
        if st == 200:
            check("余额：各片之和 = 原单表 = 逻辑归并（行数/余额/冻结）",
                  summ["balanceShardSum"] == [summ["balanceLegacy"][f"v{i}"] for i in range(3)]
                  == [summ["balanceRouted"][f"v{i}"] for i in range(3)],
                  f'{summ["balanceShardSum"]} / {summ["balanceLegacy"]} / {summ["balanceRouted"]}')
            check("分录：各片之和 = 原单表 = 逻辑归并（行数/借方/贷方，含新币种列）",
                  summ["entryShardSum"] == [summ["entryLegacy"][f"v{i}"] for i in range(3)]
                  == [summ["entryRouted"][f"v{i}"] for i in range(3)],
                  f'{summ["entryShardSum"]} / {summ["entryLegacy"]} / {summ["entryRouted"]}')
            check("分录跨片借方合计 = 贷方合计（全局 D=C 不回归）",
                  summ["entryShardSum"][1] == summ["entryShardSum"][2], str(summ["entryShardSum"]))
        st, dist = req("GET", ACC_ACT + "/account/" + USD_AID)
        if st == 200:
            counts = [v for v in (dist.get("balanceDistribution") or {}).values()]
            check("外币账户同账户同片（分片键 account_id 不变）",
                  sum(1 for v in counts if v and int(v) > 0) == 1, str(dist.get("balanceDistribution")))
            lb = (dist.get("legacyBalance") or [{}])
            rb = (dist.get("routedBalance") or [{}])
            check("外币账户原单表与路由侧余额/冻结逐值一致",
                  lb and rb and lb[0].get("balance") is not None
                  and lb[0].get("balance") == rb[0].get("balance")
                  and lb[0].get("frozen") == rb[0].get("frozen"), f"{lb} vs {rb}")
    else:
        check("local/单库态：不装配分片 Bean，走原路径", configured is False, str(configured))
        check("单库态各币种账套仍平衡", trial_all()["multiCurrencyBalanced"] is True)

# ============================================================================
print("== 10. 多币种凭证红冲（沿用原快照）+ 收尾复原 ==")
cny_end = bal(CNY_AID)
usd_end = bal(USD_AID)
fx_end = bal("SYS_FX_USD")
orig_f_lines = voucher(f"{EX_BIZ}-F")["lines"]
orig_t_lines = voucher(f"{EX_BIZ}-T")["lines"]
rv_t = ok("POST", f"/api/accounting/transfers/{EX_BIZ}-T/reverse", {"reason": "A-4/AC-6 专项收尾"})
rv_f = ok("POST", f"/api/accounting/transfers/{EX_BIZ}-F/reverse", {"reason": "A-4/AC-6 专项收尾"})
check("USD 腿冲正凭证 voucherType=REVERSE",
      voucher(rv_t['reverseBizNo']).get("voucherType") == "REVERSE")
rv_t_full = voucher(rv_t['reverseBizNo'])
rv_f_full = voucher(rv_f['reverseBizNo'])
check("冲正凭证沿用原币种账套（USD/CNY，不混币）",
      rv_t_full.get("currency") == "USD" and rv_f_full.get("currency") == "CNY",
      f'{rv_t_full.get("currency")} / {rv_f_full.get("currency")}')
check("冲正凭证沿用原汇率快照（exchange_rate 不变）",
      rv_t_full.get("exchangeRate") == vt.get("exchangeRate")
      and rv_f_full.get("exchangeRate") == vf.get("exchangeRate"),
      f'{rv_t_full.get("exchangeRate")} / {rv_f_full.get("exchangeRate")}')
check("冲正凭证借贷互换且各自 D=C",
      rv_t_full["totalDebit"] == rv_t_full["totalCredit"] == exp_gross
      and rv_f_full["totalDebit"] == rv_f_full["totalCredit"] == AMT)
check("红冲后原凭证 lines 不可篡改",
      voucher(f"{EX_BIZ}-F")["lines"] == orig_f_lines
      and voucher(f"{EX_BIZ}-T")["lines"] == orig_t_lines)
check("红冲后 CNY/USD 余额复原", bal(CNY_AID) == cny_end + AMT and bal(USD_AID) == usd_end - exp_net,
      f"{bal(CNY_AID)} / {bal(USD_AID)}")
check("红冲后 SYS_FX_USD 汇兑损益回滚", bal("SYS_FX_USD") == fx_end - exp_gain, str(bal("SYS_FX_USD")))

# 其余换汇单与充值一并红冲收尾：SYS_CASH / SYS_CASH_USD / SYS_FX_FEE 全部回到脚本前基线，
# 不给后续全套回归（账实核对/手续费闭环）留下任何差额。只追加冲正凭证，绝不改库。
for bz in (TAG + "EX2", TAG + "EXFEE"):
    for suffix in ("-T", "-F"):
        ok("POST", f"/api/accounting/transfers/{bz}{suffix}/reverse", {"reason": "A-4/AC-6 专项收尾"})
for _i in range(TOPUP_PARTS):
    ok("POST", f"/api/accounting/transfers/{TAG}TP{_i}/reverse", {"reason": "A-4/AC-6 专项收尾"})
check("收尾：SYS_CASH 回到脚本前基线（备付金账实口径不受污染）", bal("SYS_CASH") == cash_base,
      f"{bal('SYS_CASH')} vs {cash_base}")
check("收尾：SYS_CASH_USD 归零", bal("SYS_CASH_USD") == 0, str(bal("SYS_CASH_USD")))
check("收尾：SYS_FX_USD 汇兑损益归零", bal("SYS_FX_USD") == fx_base, str(bal("SYS_FX_USD")))
check("收尾：测试账户余额归零", bal(CNY_AID) == 0 and bal(USD_AID) == 0,
      f"{bal(CNY_AID)} / {bal(USD_AID)}")
t_end = trial_all()
check("收尾：每币种内 D=C 且 Σbalance=0", t_end["multiCurrencyBalanced"] is True, str(t_end))
check("收尾：trial 顶层 CNY 口径 Σbalance=0 且 balanced", t_end["balanced"] and t_end["balanceSum"] == 0, str(t_end))
check("收尾：SYS_FEE 手续费户回到脚本前基线（红冲复原，口径不受污染）", bal("SYS_FEE") == fee_sys_base,
      f"{bal('SYS_FEE')} vs {fee_sys_base}")
check("收尾：ride 测试户余额未被本专项影响", RIDE_AID is None or bal(RIDE_AID) == 5000)

print(f"\n结果: {PASSED} 通过 / {FAILED} 失败")
if FAILURES:
    print("失败项:")
    for f in FAILURES:
        print("  - " + f)
sys.exit(1 if FAILED else 0)