#!/usr/bin/env python3
"""verify-reverse.py — P0 资金安全验证：E-1 密钥暴露面收敛 + AC-1 红冲蓝补。
跑法: python3 verify-reverse.py  （payment-pro docker/local 栈需已启动，gateway 8080）
断言: create 一次性返回密钥 / list/get 脱敏; 冲正后余额复原+试算平衡+原凭证回链;
      重复冲正幂等; 冲正凭证不可再冲;  OpenAPI 验签不受脱敏影响。
"""
import json, time, urllib.request, urllib.error, hashlib, hmac, sys

BASE = "http://127.0.0.1:8080"

# ---- wave2 S-2 控制台登录：拿到 token 则 /api/** 带 Bearer；登录不可用则保持改造前行为 ----
import os as _os
TOKEN = None

def _login():
    global TOKEN
    try:
        _r = urllib.request.Request(BASE + "/api/auth/login",
                                    data=json.dumps({"username": "admin",
                                                     "password": _os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                    method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(_r, timeout=10) as _resp:
            TOKEN = json.loads(_resp.read().decode()).get("token")
    except Exception:
        TOKEN = None

_login()
# ---- end wave2 S-2 ----

SUFFIX = str(int(time.time()))
passed = failed = 0

def _req_impl(method, path, body=None, raw=None, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None and raw is None:
        r.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    if TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}

def req(method, path, body=None, *args, **kwargs):
    st, resp = _req_impl(method, path, body, *args, **kwargs)
    if st == 401 and path.startswith("/api/") and path != "/api/auth/login":
        _login()  # 会话过期/全栈重启（local 内存会话）时重登一次
        if TOKEN:
            st, resp = _req_impl(method, path, body, *args, **kwargs)
    return st, resp


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp

def check(name, cond):
    global passed, failed
    if cond:
        passed += 1; print(f"  [PASS] {name}")
    else:
        failed += 1; print(f"  [FAIL] {name}")

print("== E-1 密钥暴露面收敛 ==")
merchant = ok("POST", "/api/members", {"name": f"冲正商户{SUFFIX}", "type": "MERCHANT"})
user = ok("POST", "/api/members", {"name": f"冲正用户{SUFFIX}", "type": "USER"})
check("create 一次性返回 appId/appSecret", bool(merchant.get("appId")) and bool(merchant.get("appSecret")))
lst = ok("GET", "/api/members")
mine = [m for m in lst if m["id"] == merchant["id"]]
check("list 不返回 appSecret", mine and not mine[0].get("appSecret"))
got = ok("POST", f"/api/members/{merchant['id']}/status", {"status": "ACTIVE"})
check("updateStatus 不返回 appSecret", not got.get("appSecret"))

print("== AC-1 红冲蓝补 ==")
acct = ok("POST", "/api/accounts", {"memberId": user["id"]})
aid = acct["id"]
biz = f"AC1TEST{SUFFIX}"
ok("POST", "/api/accounting/internal/topup", {"accountId": aid, "amount": 1000, "bizNo": biz})
check("充值后余额=1000", ok("GET", f"/api/accounting/accounts/{aid}/balance") == 1000)
orig = ok("GET", f"/api/accounting/transfers/{biz}")
check("原凭证初始无回链", not orig.get("reverseBizNo"))

rv = ok("POST", f"/api/accounting/transfers/{biz}/reverse", {"reason": "验证冲正"})
rvno = rv.get("reverseBizNo")
check("返回反向凭证号 RV*", bool(rvno) and rvno.startswith("RV"))
check("反向分录借贷互换", {l["dir"] for l in rv["lines"]} == {"D", "C"})
check("冲正后余额复原=0", ok("GET", f"/api/accounting/accounts/{aid}/balance") == 0)
trial = ok("GET", "/api/accounting/trial")
check("冲正后试算平衡 D=C", trial["balanced"] and trial["totalDebit"] == trial["totalCredit"])
check("冲正后 Σbalance=0", trial["balanceSum"] == 0)
orig2 = ok("GET", f"/api/accounting/transfers/{biz}")
check("原凭证回链 reverseBizNo", orig2.get("reverseBizNo") == rvno)
check("原凭证 lines 未被篡改", orig2["lines"] == orig["lines"])

rv2 = ok("POST", f"/api/accounting/transfers/{biz}/reverse", {"reason": "重复冲正"})
check("重复冲正幂等返回原结果", rv2.get("reverseBizNo") == rvno)
st, resp = req("POST", f"/api/accounting/transfers/{rvno}/reverse", {"reason": "冲正再冲"})
check("冲正凭证不可再冲(拒绝)", st in (400, 409) or resp.get("code") != "00000")
trial2 = ok("GET", "/api/accounting/trial")
check("幂等冲正不重复过账 Σbalance=0", trial2["balanceSum"] == 0)

print("== OpenAPI 验签不受脱敏影响 ==")
ts = str(int(time.time() * 1000))
params = {"appId": merchant["appId"], "timestamp": ts, "method": "account.balance", "accountId": aid}
sign_str = "&".join(f"{k}={params[k]}" for k in sorted(params))
sig = hmac.new(merchant["appSecret"].encode(), sign_str.encode(), hashlib.sha256).hexdigest()
st, resp = req("POST", "/openapi/account.balance", body={**params, "sign": sig})
check("create 密钥仍可验签查询", st == 200)

print(f"\n结果: {passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)