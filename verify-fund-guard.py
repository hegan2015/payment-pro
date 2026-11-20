#!/usr/bin/env python3
"""verify-fund-guard.py — S-7 资金防线护航验证（备付金账实核对 + 大额双人复核 + 二次认证）。
跑法: python3 verify-fund-guard.py                （核心断言 + 布置重启混沌现场）
      ./stop.sh && ./run.sh --docker --no-build   （全栈重启，13/13 健康后）
      python3 verify-fund-guard.py --post-restart （复核单不丢 + approve 后动账成功）

新增端点（裸数据返回）:
  POST /api/security/step-up            body={username?,password} -> {token,operator,expiresIn}
  GET  /api/reviews?status=             -> [ReviewDTO]
  POST /api/reviews                     body={bizType,bizNo,amount} -> ReviewDTO（submitter 恒取会话用户）
  POST /api/reviews/{id}/approve        需 X-Step-Up-Token，approver 恒取 token 操作员（会话用户），body 冒充他人 403
  POST /api/reviews/{id}/reject         body={reason}
  POST /api/accounting/reserve/check    -> ReserveCheckDTO{bookBalance,actualBalance,diff,pass}
  GET  /api/accounting/reserve/logs     -> [ReserveCheckDTO]
  POST /api/accounting/reserve/adjust   body={amount,reason}（模拟银行侧事件，需 step-up）

断言（卡 5 项 + 幂等 + 混沌）:
  ① 大额退款未复核 → A0428 REVIEW_PENDING 拒绝且余额分毫不动
  ② 复核 approve 后退款成功（原 TCC 流程）
  ③ 同人提交+审批被拒（双人）
  ④ 备付金差异 → ERROR 告警日志 + payment_accounting_reserve_diff_total 指标 + 留痕
  ⑤ 敏感操作无 step-up token → 403
  另: 复核幂等重复提交不重复建单 / reject 后仍拒绝动账 / 重启后复核单不丢、未复核绝不扣款。
认证双态兼容: 先试 POST /api/auth/login（B 泳道），200 则带 Bearer，否则不带。
测试数据独立前缀 FGTEST+uuid，新开户+充值构造大额场景，不改既有账户数据。
"""
import json, os, sys, time, urllib.request, urllib.error, uuid

BASE = "http://127.0.0.1:8080"
ACCOUNTING_METRICS = "http://127.0.0.1:18083/actuator/prometheus"
ADMIN_PW = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
SUFFIX = uuid.uuid4().hex[:10]
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run", ".fund-guard-state.json")
POST_RESTART = "--post-restart" in sys.argv
passed = failed = 0
BEARER = None


def req(method, path, body=None, token=None, bearer=True, base=BASE, auth=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if bearer and (auth or BEARER):
        r.add_header("Authorization", "Bearer " + (auth or BEARER))
    if token:
        r.add_header("X-Step-Up-Token", token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def ok(method, path, body=None, token=None):
    st, resp = req(method, path, body, token=token)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {detail}")


def poll(fn, cond, timeout=15):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if cond(last):
            return last
        time.sleep(0.5)
    return last


def bal(aid):
    return ok("GET", f"/api/accounting/accounts/{aid}/balance")


def step_up(password=None, auth=None):
    """二次认证绑会话：再验的是当前登录用户自己的口令，operator 恒为会话用户。"""
    st, resp = req("POST", "/api/security/step-up",
                   {"password": password or ADMIN_PW}, auth=auth)
    assert st == 200 and resp.get("token"), f"step-up 颁发失败 {st} {resp}"
    return resp["token"]


def reviews(status=None):
    return ok("GET", "/api/reviews" + (f"?status={status}" if status else ""))


def find_review(biz_no):
    return next((r for r in reviews() if r["bizNo"] == biz_no), None)


def pay_order(aid, amount, subject):
    order = ok("POST", "/api/orders", {"merchantId": MERCHANT_ID, "subject": subject, "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay",
           {"orderId": order["id"], "accountId": aid, "method": "BALANCE", "requestId": f"FGPAY{SUFFIX}-{uuid.uuid4().hex[:8]}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
    assert p["status"] == "SUCCESS", f"支付未成功 {p}"
    return pid


# ---- 认证双态兼容：B 泳道登录若已落地则带 Bearer ----
st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PW}, bearer=False)
if st == 200 and isinstance(resp, dict):
    BEARER = resp.get("token") or resp.get("accessToken") or (resp.get("data") or {}).get("token")
print(f"== 认证双态: {'Bearer 会话已落地' if BEARER else 'B 未落地（无鉴权态）'} ==")

# 双人复核必须是两个真实控制台用户：step-up 绑会话后 body 冒充他人会被 403。
BOB_USER = f"fgbob{SUFFIX}"
BOB_PW = "FgBob#2026x"
BOB_TOKEN = None
if BEARER:
    st, resp = req("POST", "/api/console/users",
                   {"username": BOB_USER, "password": BOB_PW, "roles": ["OPERATOR"]})
    assert st == 200, f"创建第二审批人失败 {st} {resp}"
    st, resp = req("POST", "/api/auth/login", {"username": BOB_USER, "password": BOB_PW}, bearer=False)
    assert st == 200 and resp.get("token"), f"第二审批人登录失败 {st} {resp}"
    BOB_TOKEN = resp["token"]
    print(f"== 第二审批人（OPERATOR）: {BOB_USER} ==")

if POST_RESTART:
    print("== 混沌: 重启后复核单不丢、未复核绝不扣款 ==")
    with open(STATE_FILE) as f:
        s = json.load(f)
    aid, pid2, ref2, rid2 = s["aid"], s["payment2"], s["requestId2"], s["reviewId2"]
    rv = find_review(ref2)
    check("重启后复核单仍在且 PENDING", rv is not None and rv["id"] == rid2 and rv["status"] == "PENDING", str(rv))
    p2 = ok("GET", f"/api/payments/{pid2}")
    check("重启后未复核分毫未动（refundedAmount=0）", p2["refundedAmount"] == 0, str(p2))
    check("重启后付款账户余额不变", bal(aid) == s["balanceBefore"], f'{bal(aid)} != {s["balanceBefore"]}')
    tk = step_up()
    st, resp = req("POST", f"/api/payments/{pid2}/refund", {"amount": s["amount2"], "requestId": ref2})
    check("重启后无 token 退款仍 403", st == 403, f"{st} {resp}")
    st, resp = req("POST", f"/api/payments/{pid2}/refund", {"amount": s["amount2"], "requestId": ref2}, token=tk)
    check("重启后未复核退款仍 A0428 拒绝", st == 400 and resp.get("code") == "A0428", f"{st} {resp}")
    st, resp = req("POST", f"/api/reviews/{rid2}/approve", {},
                   token=step_up(BOB_PW, BOB_TOKEN), auth=BOB_TOKEN)
    check("重启后 approve 成功", st == 200 and resp.get("status") == "APPROVED", f"{st} {resp}")
    r = ok("POST", f"/api/payments/{pid2}/refund", {"amount": s["amount2"], "requestId": ref2}, token=tk)
    check("重启后复核通过退款成功", r["refundAmount"] == s["amount2"], str(r))
    poll(lambda: ok("GET", f"/api/payments/{pid2}"), lambda x: x["refundedAmount"] == s["amount2"])
    check("重启后退款到账余额复原", bal(aid) == s["balanceBefore"] + s["amount2"])
    os.remove(STATE_FILE)
    print(f"\n结果: {passed} 通过 / {failed} 失败")
    sys.exit(1 if failed else 0)

print("== 二次认证颁发 ==")
tk = step_up()
check("口令再验颁发短时 token", bool(tk))
st, resp = req("POST", "/api/security/step-up", {"password": "wrong-password"})
check("错误口令拒绝 403", st == 403, f"{st} {resp}")

print("== 场景构造（FGTEST 独立前缀，新开户+充值） ==")
MERCHANT_ID = ok("POST", "/api/members", {"name": f"FGTEST商户{SUFFIX}", "type": "MERCHANT"})["id"]
ok("POST", "/api/accounts", {"memberId": MERCHANT_ID})
user = ok("POST", "/api/members", {"name": f"FGTEST用户{SUFFIX}", "type": "USER"})
aid = ok("POST", "/api/accounts", {"memberId": user["id"]})["id"]
ok("POST", "/api/accounting/internal/topup", {"accountId": aid, "amount": 2_000_000, "bizNo": f"FGTOP{SUFFIX}"})
pid1 = pay_order(aid, 1_500_000, "FGTEST大额商品1")
bal0 = bal(aid)
check("大额支付成功且余额正确", bal0 == 500_000, str(bal0))

print("== ⑤ 敏感操作无 step-up token → 403 ==")
ref1 = f"FGREF1-{SUFFIX}"
st, resp = req("POST", f"/api/payments/{pid1}/refund", {"amount": 1_200_000, "requestId": ref1})
check("大额退款无 token → 403", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
check("403 后余额分毫不动", bal(aid) == bal0)

print("== ① 大额退款未复核 → A0428 且余额不动 ==")
st, resp = req("POST", f"/api/payments/{pid1}/refund", {"amount": 1_200_000, "requestId": ref1}, token=tk)
check("未复核大额退款 A0428 REVIEW_PENDING", st == 400 and resp.get("code") == "A0428", f"{st} {resp}")
rid1 = resp.get("reviewId")
check("返回复核单 reviewId", bool(rid1), str(resp))
check("拒绝后余额分毫不动", bal(aid) == bal0)
check("拒绝后支付单 refundedAmount=0", ok("GET", f"/api/payments/{pid1}")["refundedAmount"] == 0)
rv = find_review(ref1)
check("复核单 PENDING 且 bizType=REFUND", rv is not None and rv["status"] == "PENDING" and rv["bizType"] == "REFUND", str(rv))

print("== 复核幂等：重复提交不重复建单 ==")
again = ok("POST", "/api/reviews", {"bizType": "REFUND", "bizNo": ref1, "amount": 1_200_000})
check("显式重复提交返回同一复核单", again["id"] == rid1, str(again))
st, resp = req("POST", f"/api/payments/{pid1}/refund", {"amount": 1_200_000, "requestId": ref1}, token=tk)
check("重试退款仍 A0428 且同一复核单", st == 400 and resp.get("code") == "A0428" and resp.get("reviewId") == rid1, f"{st} {resp}")
check("同 bizNo 复核单仅一张", sum(1 for r in reviews() if r["bizNo"] == ref1) == 1)

print("== ③ 同人提交+审批被拒（双人） ==")
dual_biz = f"FGDUAL-{SUFFIX}"
dual = ok("POST", "/api/reviews", {"bizType": "REFUND", "bizNo": dual_biz, "amount": 1_500_000})
check("提交人恒为会话用户（不可冒充）", dual["submitter"] == "admin", str(dual))
st, resp = req("POST", f"/api/reviews/{dual['id']}/approve", {}, token=tk)
check("同人审批被拒", st != 200 and resp.get("code") not in (None, "00000"), f"{st} {resp}")
check("同人审批后仍 PENDING", find_review(dual_biz)["status"] == "PENDING")
st, resp = req("POST", f"/api/reviews/{dual['id']}/approve", {})
check("复核 approve 无 token → 403", st == 403, f"{st} {resp}")
st, resp = req("POST", f"/api/reviews/{dual['id']}/approve", {"approver": BOB_USER}, token=tk)
check("body 冒充他人审批 → 403（双人不可绕过）", st == 403, f"{st} {resp}")
st, resp = req("POST", "/api/security/step-up", {"username": BOB_USER, "password": ADMIN_PW})
check("step-up 冒充他人用户名 → 403", st == 403, f"{st} {resp}")

print("== ② approve 后退款成功（原 TCC 流程） ==")
st, resp = req("POST", f"/api/reviews/{rid1}/approve", {},
               token=step_up(BOB_PW, BOB_TOKEN), auth=BOB_TOKEN)
check("双人 approve 成功 APPROVED", st == 200 and resp.get("status") == "APPROVED" and resp.get("approver") == BOB_USER, f"{st} {resp}")
r = ok("POST", f"/api/payments/{pid1}/refund", {"amount": 1_200_000, "requestId": ref1}, token=tk)
check("复核通过后退款受理成功", r["refundAmount"] == 1_200_000, str(r))
p = poll(lambda: ok("GET", f"/api/payments/{pid1}"), lambda x: x["refundedAmount"] == 1_200_000)
check("退款 TCC 终态 refundedAmount=1200000", p["refundedAmount"] == 1_200_000, str(p))
check("退款到账余额复原", bal(aid) == bal0 + 1_200_000)
st, resp = req("POST", f"/api/reviews/{rid1}/approve", {}, token=tk)
check("终态复核单重复审批被拒（CAS）", st == 409, f"{st} {resp}")

print("== reject 后仍拒绝动账 ==")
rej_biz = f"FGREJ-{SUFFIX}"
rej = ok("POST", "/api/reviews", {"bizType": "REFUND", "bizNo": rej_biz, "amount": 1_200_000})
st, resp = req("POST", f"/api/reviews/{rej['id']}/reject", {"reason": "FGTEST 拒绝"}, token=tk)
check("reject 成功 REJECTED 留痕", st == 200 and resp.get("status") == "REJECTED" and resp.get("rejectReason") == "FGTEST 拒绝", f"{st} {resp}")
st, resp = req("POST", f"/api/payments/{pid1}/refund", {"amount": 1_200_000, "requestId": rej_biz}, token=tk)
check("REJECTED 复核单退款仍 A0428", st == 400 and resp.get("code") == "A0428" and resp.get("reviewStatus") == "REJECTED", f"{st} {resp}")

print("== ④ 备付金账实核对：差异 → 告警 + 指标 + 留痕 ==")
c0 = ok("POST", "/api/accounting/reserve/check")
check("基线核对 pass=true diff=0", c0["pass"] is True and c0["diff"] == 0, str(c0))
check("bookBalance=SYS_CASH 账务余额", c0["bookBalance"] == bal("SYS_CASH"), str(c0))
st, resp = req("POST", "/api/accounting/reserve/adjust", {"amount": 500, "reason": "FGTEST 模拟银行侧差错"})
check("模拟调整无 token → 403", st == 403, f"{st} {resp}")
c1 = ok("POST", "/api/accounting/reserve/adjust", {"amount": 500, "reason": "FGTEST 模拟银行侧差错"}, token=tk)
check("注入银行侧差异后 pass=false diff=-500", c1["pass"] is False and c1["diff"] == -500, str(c1))
logs = ok("GET", "/api/accounting/reserve/logs?limit=5")
check("核对留痕含差异记录", any(l["id"] == c1["id"] and l["pass"] is False and l["diff"] == -500 for l in logs), str(logs))
try:
    with urllib.request.urlopen(ACCOUNTING_METRICS, timeout=10) as resp:
        metrics = resp.read().decode()
    diff_lines = [l for l in metrics.splitlines() if l.startswith("payment_accounting_reserve_diff_total")]
    check("差异指标 payment_accounting_reserve_diff_total ≥ 1",
          bool(diff_lines) and any(float(l.split()[-1]) >= 1 for l in diff_lines), str(diff_lines))
except Exception as e:
    check("差异指标 payment_accounting_reserve_diff_total ≥ 1", False, str(e))
log_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs", "accounting.log")
try:
    with open(log_path, encoding="utf-8", errors="ignore") as f:
        f.seek(max(0, os.path.getsize(log_path) - 200_000))
        tail = f.read()
    check("ERROR 告警日志留痕", "备付金账实差异告警" in tail and c1["id"] in tail)
except Exception as e:
    check("ERROR 告警日志留痕", False, str(e))
c2 = ok("POST", "/api/accounting/reserve/adjust", {"amount": -500, "reason": "FGTEST 差异复原"}, token=tk)
check("差异复原后 pass=true", c2["pass"] is True and c2["diff"] == 0, str(c2))
check("核对只读账务：SYS_CASH 余额未被调整触碰", bal("SYS_CASH") == c0["bookBalance"])

print("== 混沌布置：复核中宕机现场（PENDING 复核单 + 未动账） ==")
pid2 = pay_order(aid, 1_500_000, "FGTEST大额商品2")
ref2 = f"FGREF2-{SUFFIX}"
bal_before = bal(aid)
st, resp = req("POST", f"/api/payments/{pid2}/refund", {"amount": 1_200_000, "requestId": ref2}, token=tk)
check("第二笔大额退款进入 PENDING 复核", st == 400 and resp.get("code") == "A0428", f"{st} {resp}")
rid2 = resp.get("reviewId")
check("混沌现场余额未动", bal(aid) == bal_before)
os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
with open(STATE_FILE, "w") as f:
    json.dump({"suffix": SUFFIX, "aid": aid, "payment2": pid2, "requestId2": ref2,
               "reviewId2": rid2, "amount2": 1_200_000, "balanceBefore": bal_before}, f)
print(f"  现场已保存 {STATE_FILE}；请全栈重启后跑: python3 verify-fund-guard.py --post-restart")

print(f"\n结果: {passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)