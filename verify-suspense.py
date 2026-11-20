#!/usr/bin/env python3
"""verify-suspense.py — S-6 红冲蓝补护航验证：挂账科目 SYS_SUSPENSE + 凭证不可篡改。
跑法: python3 verify-suspense.py  （payment-pro docker/local 栈需已启动，gateway 8080）

依赖主会话在 payment-gateway ApiController 补充的 REST 端点（裸数据返回，非 Result 信封）:
  POST /api/accounting/suspense          body=PostRequest{bizNo,subject,refId,lines}
                                         -> TransferDTO{bizNo,lines,voucherType,...}
                                         （AccountingFacade.suspensePost，(SUSPENSE,bizNo) 幂等）
  GET  /api/accounting/suspense/balance  -> Long（AccountingFacade.suspenseBalance）
复用既有端点: /api/members /api/accounts /api/accounting/internal/topup
  /api/accounting/accounts/{id}/balance /api/accounting/transfers/{bizNo}
  /api/accounting/transfers/{bizNo}/reverse /api/accounting/trial

断言: 挂账后试算平衡(D=C 且 Σbalance=0) / SYS_SUSPENSE 余额=差错差额 / 重复挂账幂等 /
      原凭证 lines 不可篡改(同 bizNo 改 lines 被拒或幂等吸收、reverse 后原 lines 不变) /
      蓝补核销后挂账归零 / 非法挂账(不含 SYS_SUSPENSE、借贷不平衡)拒绝。
基线差额口径: SYS_SUSPENSE 为全局科目，断言用「相对基线增量」，脚本可重复跑。
"""
import json, time, urllib.request, urllib.error, sys

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

def _req_impl(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
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

def line(account, dir_, amount):
    return {"accountId": account, "dir": dir_, "amount": amount}

def balanced(lines):
    d = sum(l["amount"] for l in lines if l["dir"] == "D")
    c = sum(l["amount"] for l in lines if l["dir"] == "C")
    return d == c and d > 0

def suspense_balance():
    return ok("GET", "/api/accounting/suspense/balance")

print("== S-6 差错挂账（长款） ==")
user = ok("POST", "/api/members", {"name": f"挂账用户{SUFFIX}", "type": "USER"})
acct = ok("POST", "/api/accounts", {"memberId": user["id"]})
aid = acct["id"]
biz = f"S6TOPUP{SUFFIX}"
ok("POST", "/api/accounting/internal/topup", {"accountId": aid, "amount": 1000, "bizNo": biz})
base = suspense_balance()
trial0 = ok("GET", "/api/accounting/trial")
check("基线试算平衡 Σbalance=0", trial0["balanced"] and trial0["balanceSum"] == 0)
check("充值凭证 voucherType=NORMAL", ok("GET", f"/api/accounting/transfers/{biz}").get("voucherType") == "NORMAL")

long_biz = f"S6LONG{SUFFIX}"
long_lines = [line("SYS_CASH", "D", 300), line("SYS_SUSPENSE", "C", 300)]
long_v = ok("POST", "/api/accounting/suspense",
            {"bizNo": long_biz, "subject": "长款挂账", "refId": biz, "lines": long_lines})
check("挂账凭证返回 bizNo", long_v.get("bizNo") == long_biz)
check("挂账凭证 voucherType=SUSPENSE", long_v.get("voucherType") == "SUSPENSE")
check("挂账分录借贷平衡", balanced(long_v.get("lines") or []))
check("SYS_SUSPENSE 余额=基线+长款差额300", suspense_balance() == base + 300)
trial1 = ok("GET", "/api/accounting/trial")
check("挂账后试算 D=C", trial1["balanced"] and trial1["totalDebit"] == trial1["totalCredit"])
check("挂账后 Σbalance=0", trial1["balanceSum"] == 0)
check("trial.suspenseBalance 与科目余额一致", trial1.get("suspenseBalance") == base + 300)

print("== 重复挂账幂等 ==")
long_v2 = ok("POST", "/api/accounting/suspense",
             {"bizNo": long_biz, "subject": "长款挂账", "refId": biz, "lines": long_lines})
check("重复挂账返回原凭证", long_v2.get("bizNo") == long_biz)
check("重复挂账不重复过账（余额不变）", suspense_balance() == base + 300)
check("重复挂账分录数不变", ok("GET", "/api/accounting/trial")["entries"] == trial1["entries"])

print("== 凭证不可篡改 ==")
tamper_lines = [line("SYS_CASH", "D", 999), line("SYS_SUSPENSE", "C", 999)]
st, resp = req("POST", "/api/accounting/suspense",
               {"bizNo": long_biz, "subject": "篡改尝试", "lines": tamper_lines})
check("同 bizNo 改 lines 被拒或幂等吸收（未产生新过账）",
      st in (400, 409) or resp.get("bizNo") == long_biz)
orig_long = ok("GET", f"/api/accounting/transfers/{long_biz}")
check("挂账凭证 lines 未被篡改", orig_long["lines"] == long_lines)
check("篡改尝试后余额不变", suspense_balance() == base + 300)

orig_topup = ok("GET", f"/api/accounting/transfers/{biz}")
rv = ok("POST", f"/api/accounting/transfers/{biz}/reverse", {"reason": "S-6 护航验证"})
rvno = rv.get("reverseBizNo")
check("冲正凭证 voucherType=REVERSE",
      bool(rvno) and ok("GET", f"/api/accounting/transfers/{rvno}").get("voucherType") == "REVERSE")
check("reverse 后原凭证 lines 不变", ok("GET", f"/api/accounting/transfers/{biz}")["lines"] == orig_topup["lines"])
check("reverse 后余额复原=0", ok("GET", f"/api/accounting/accounts/{aid}/balance") == 0)

print("== 短款挂账 + 蓝补核销 ==")
short_biz = f"S6SHORT{SUFFIX}"
short_lines = [line("SYS_SUSPENSE", "D", 100), line("SYS_CASH", "C", 100)]
short_v = ok("POST", "/api/accounting/suspense",
             {"bizNo": short_biz, "subject": "短款挂账", "lines": short_lines})
check("短款凭证 voucherType=SUSPENSE", short_v.get("voucherType") == "SUSPENSE")
check("短款后余额=基线+长款300-短款100", suspense_balance() == base + 200)

wo1_biz = f"S6WO1{SUFFIX}"
wo1_lines = [line("SYS_SUSPENSE", "D", 300), line("SYS_CASH", "C", 300)]
wo1 = ok("POST", "/api/accounting/suspense",
         {"bizNo": wo1_biz, "subject": "长款核销(蓝补)", "refId": long_biz, "lines": wo1_lines})
check("核销凭证 voucherType=SUSPENSE", wo1.get("voucherType") == "SUSPENSE")
check("长款核销后余额=基线+短款200-核销300", suspense_balance() == base - 100)
check("核销只追加新凭证，原挂账 lines 不变",
      ok("GET", f"/api/accounting/transfers/{long_biz}")["lines"] == long_lines)

wo2_biz = f"S6WO2{SUFFIX}"
wo2_lines = [line("SYS_CASH", "D", 100), line("SYS_SUSPENSE", "C", 100)]
ok("POST", "/api/accounting/suspense",
   {"bizNo": wo2_biz, "subject": "短款核销(蓝补)", "refId": short_biz, "lines": wo2_lines})
check("全部核销后挂账归零（回到基线）", suspense_balance() == base)
trial2 = ok("GET", "/api/accounting/trial")
check("核销后试算 D=C 且 Σbalance=0",
      trial2["balanced"] and trial2["totalDebit"] == trial2["totalCredit"] and trial2["balanceSum"] == 0)
check("核销后 trial.suspenseBalance=基线", trial2.get("suspenseBalance") == base)

print("== 非法挂账拒绝 ==")
st, resp = req("POST", "/api/accounting/suspense",
               {"bizNo": f"S6BAD1{SUFFIX}", "lines": [line("SYS_CASH", "D", 10), line("SYS_FEE", "C", 10)]})
check("不含 SYS_SUSPENSE 的挂账被拒", st in (400, 409) or resp.get("code") not in (None, "00000"))
st, resp = req("POST", "/api/accounting/suspense",
               {"bizNo": f"S6BAD2{SUFFIX}", "lines": [line("SYS_SUSPENSE", "D", 10), line("SYS_CASH", "C", 9)]})
check("借贷不平衡的挂账被拒", st in (400, 409) or resp.get("code") not in (None, "00000"))

print(f"\n结果: {passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)