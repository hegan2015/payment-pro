#!/usr/bin/env python3
# ============================================================================
# payment-pro P2 第三期 · G 泳道验证：A-5 开销户/升降级 + ST-6 保证金/垫资
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-account-lifecycle.py
#   [1] 基线：试算 D=C 且 Σbalance=0（含 byCurrency）+ ride 既有账户快照
#   [2] 开户幂等：A-1 (memberId,accountType) / A-4 (+currency) uk 与懒开户语义不破坏
#   [3] A-5 销户护栏：余额非0 / 有金额冻结 / 状态冻结 / 在途提现 / 未释放保证金 /
#       未回收垫资 —— 一律拒绝（受理前拒绝，不落悬挂单、绝不动资金）
#   [4] A-5 销户成功：CLOSING→CLOSED 状态机 + 留痕 + **不物理删** + 销户后不可用 + 幂等
#   [5] A-5 升降级：状态机 + KYC 联动 + 限额联动 + 降级余额护栏 + 敏感操作 step-up
#   [6] ST-6 保证金：缴存/释放/扣划金额守恒 + SYS_DEPOSIT 恒等式 + 幂等 + 超额拒绝 + step-up
#   [7] ST-6 垫资：授信额度内垫付/追偿 + 并发不超授信 + 幂等 + SYS_ADVANCE 恒等式
#   [8] ST-6 联动：保证金扣划偿还垫资（D SYS_DEPOSIT / C SYS_ADVANCE）守恒
#   [9] 资金护航：试算 D=C 且 Σbalance=0（含 byCurrency）+ **ride 既有账户不受影响**
#  [10] 分片/单库两态结果一致（一键回滚再切回，逐条一致）
#
# 数据保护：只新增业务行（ALCTEST/DEPTEST 前缀 + uuid），绝不删除/重置任何既有数据；
#           不修改任何共享配置文件；销户是逻辑状态（账户行永久保留）。
# 断言口径：不放宽、不改库状态凑绿；失败如实记录。
# ============================================================================
import json
import os
import random
import string
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

BASE = "http://127.0.0.1:8080"
SETTLE_ACT = "http://127.0.0.1:18089/actuator"
SUFFIX = uuid.uuid4().hex[:6]
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []

ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
ID_CODES = "10X98765432"


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ✓ {name}")
    else:
        FAILED += 1
        FAILURES.append(name)
        print(f"  ✗ {name}  {detail}")
    return bool(cond)


def http(url, method="GET", body=None, bearer=False, step=False, timeout=120, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json", "Accept": "*/*"})
    if bearer and TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    if step and STEP:
        r.add_header("X-Step-Up-Token", STEP)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            if raw:
                return resp.status, text
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        if raw:
            return e.code, text
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}


def login():
    global TOKEN, STEP
    pwd = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": "admin", "password": pwd})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": pwd}, bearer=True)
    assert st == 200, f"二次认证失败 {st} {resp}"
    STEP = resp["token"]


def api(method, path, body=None, step=False):
    st, resp = http(BASE + path, method, body, bearer=True, step=step)
    if st == 401:
        login()
        st, resp = http(BASE + path, method, body, bearer=True, step=step)
    return st, resp


def ok(method, path, body=None, step=False):
    st, resp = api(method, path, body, step=step)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def act(path="", body=None):
    st, resp = http(SETTLE_ACT + ("/" + path if path else ""), "POST" if body is not None else "GET", body)
    assert st == 200, f"{SETTLE_ACT}/{path} 期望 200，实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def trial_all():
    return ok("GET", "/api/accounting/trial/by-currency")


def trial_guard(tag):
    t = ok("GET", "/api/accounting/trial")
    check(f"试算 D=C 且 Σbalance=0（{tag}）",
          bool(t.get("balanced")) and t.get("totalDebit") == t.get("totalCredit")
          and t.get("balanceSum") == 0, str(t))
    a = trial_all()
    rows = a.get("byCurrency") or []
    check(f"逐币种 D=C 且 Σbalance=0（{tag}）",
          bool(rows) and all(r.get("totalDebit") == r.get("totalCredit") and r.get("balanceSum") == 0
                             and r.get("balanced") and r.get("conserved") for r in rows),
          str([{k: r.get(k) for k in ("currency", "totalDebit", "totalCredit", "balanceSum")} for r in rows]))
    return t


def gen_id_card():
    base = f"11010119900307{random.randint(1, 999):03d}"
    total = sum(int(c) * w for c, w in zip(base, ID_WEIGHTS))
    return base + ID_CODES[total % 11]


def luhn_check_digit(digits):
    total, alt = 0, True
    for c in reversed(digits):
        n = int(c)
        if alt:
            n *= 2
            if n > 9:
                n -= 9
        total += n
        alt = not alt
    return str((10 - total % 10) % 10)


def gen_card(bin_prefix="622202"):
    body = bin_prefix + "".join(random.choice(string.digits) for _ in range(9))
    return body + luhn_check_digit(body)


def gen_mobile():
    return "139" + "".join(random.choice(string.digits) for _ in range(8))


def new_member(tag, kind="MERCHANT", prefix="ALCTEST"):
    return ok("POST", "/api/members", {"name": f"{prefix}{tag}{SUFFIX}", "type": kind})


def new_account(member_id):
    return ok("POST", "/api/accounts", {"memberId": member_id})


def topup(account_id, amount, tag):
    return ok("POST", "/api/accounting/internal/topup",
              {"accountId": account_id, "amount": amount, "bizNo": f"alctop-{tag}-{SUFFIX}"})


def withdraw_out(account_id, amount, tag, card=None):
    """把账户余额出金到银行（沙箱同步 SUCCESS），用于把测试户余额归零。"""
    return ok("POST", "/api/settle/withdraw",
              {"accountId": account_id, "amount": amount, "requestId": f"alcwd-{tag}-{SUFFIX}",
               "bankCardNo": card or gen_card()}, step=True)


def close(account_id, tag, step=True, expect=200):
    st, resp = api("POST", f"/api/accounts/{account_id}/close",
                   {"requestId": f"alcclose-{tag}-{SUFFIX}", "reason": "A-5 生命周期验证"}, step=step)
    if expect is not None:
        assert st == expect, f"销户 {account_id} 期望 {expect}，实际 {st}: {resp}"
    return resp if expect == 200 else (st, resp)


def snapshot_account(account_id):
    a = ok("GET", f"/api/accounts/{account_id}")
    return {k: a.get(k) for k in ("id", "memberId", "status", "accountType", "level", "freezeType",
                                  "currency", "lifecycleStatus", "closedAt", "balance")}


def main():
    login()
    print(f"== A-5 / ST-6 验证开始 suffix={SUFFIX} ==")

    # ---------------- [1] 基线 + ride 既有账户快照 ----------------
    print("[1] 基线：试算平衡 + ride 既有账户快照（兼容性红线）")
    trial_guard("基线")
    all_accounts = ok("GET", "/api/accounts")
    ride_like = [a for a in all_accounts if not a["memberId"].startswith("ALCTEST")][:12]
    ride_snapshot = {a["id"]: snapshot_account(a["id"]) for a in ride_like}
    check("既有账户快照非空（ride/历史账户存在）", len(ride_snapshot) > 0, str(len(ride_snapshot)))
    check("既有账户 lifecycleStatus 缺省 ACTIVE（additive 列不改变存量语义）",
          all(v["lifecycleStatus"] == "ACTIVE" for v in ride_snapshot.values()),
          str({k: v["lifecycleStatus"] for k, v in list(ride_snapshot.items())[:3]}))
    check("既有账户 closedAt 均为空（未被销户）",
          all(v["closedAt"] is None for v in ride_snapshot.values()))
    ride_balance_sum = sum(v["balance"] for v in ride_snapshot.values())
    accounts_before = len(all_accounts)
    sys_deposit0 = bal("SYS_DEPOSIT")
    sys_advance0 = bal("SYS_ADVANCE")
    sys_fee0 = bal("SYS_FEE")
    dep_summary0 = ok("GET", "/api/settle/deposit/summary")
    check("基线保证金恒等式成立（SYS_DEPOSIT == 未释放保证金）",
          dep_summary0["balanced"] is True and dep_summary0["diff"] == 0, str(dep_summary0))

    # ---------------- [2] 开户幂等（uk 与懒开户语义不破坏） ----------------
    print("[2] 开户幂等：A-1 类型户 / A-4 币种户 / 既有 uk 不破坏")
    m1 = new_member("M1")
    a1 = new_account(m1["id"])
    check("新户缺省 BALANCE/CNY/level I/lifecycle ACTIVE",
          a1["accountType"] == "BALANCE" and a1["currency"] == "CNY" and a1["level"] == "I"
          and a1["lifecycleStatus"] == "ACTIVE", str(a1))
    a1_again = ok("POST", "/api/accounts/typed", {"memberId": m1["id"], "accountType": "BALANCE"})
    check("A-1 类型开户幂等（同 memberId+BALANCE 返回既有户）", a1_again["id"] == a1["id"], str(a1_again))
    a1_ccy = ok("POST", "/api/accounts/currency", {"memberId": m1["id"], "accountType": "BALANCE", "currency": "CNY"})
    check("A-4 CNY 开户与 typed 逐字节等价（同 id）", a1_ccy["id"] == a1["id"], str(a1_ccy))
    dep_a = ok("POST", "/api/accounts/typed", {"memberId": m1["id"], "accountType": "DEPOSIT"})
    dep_a2 = ok("POST", "/api/accounts/typed", {"memberId": m1["id"], "accountType": "DEPOSIT"})
    check("DEPOSIT 专户独立 id 且幂等", dep_a["id"] != a1["id"] and dep_a2["id"] == dep_a["id"], str(dep_a))
    usd_a = ok("POST", "/api/accounts/currency", {"memberId": m1["id"], "accountType": "BALANCE", "currency": "USD"})
    check("同会员同类型外币户可另开（uk=member+type+currency）",
          usd_a["id"] != a1["id"] and usd_a["currency"] == "USD", str(usd_a))
    lazy = ok("GET", f"/api/accounts?memberId={m1['id']}")
    check("懒开户口径不变：list 首位仍是 CNY BALANCE 户", lazy[0]["id"] == a1["id"], str(lazy[0]))
    check("getByMember 缺省仍取 CNY BALANCE 户",
          ok("GET", f"/api/accounts?memberId={m1['id']}")[0]["accountType"] == "BALANCE")
    logs = ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs")
    check("开户留痕 OPEN 已落库", any(x["action"] == "OPEN" for x in logs), str(logs))

    # ---------------- [3] 销户护栏 ----------------
    print("[3] A-5 销户护栏：余额非0/冻结/状态/在途/保证金/垫资 一律拒绝")
    # 3.1 余额非 0
    topup(a1["id"], 5000, "m1")
    st, resp = close(a1["id"], "m1-nonzero", expect=400)
    check("余额非 0 销户拒绝（400）", st == 400, f"{st} {resp}")
    check("拒绝原因明示余额为 0 要求", "余额为 0" in str(resp.get("error", "")), str(resp))
    check("拒绝后账户仍 ACTIVE 且余额不变（绝不动资金）",
          ok("GET", f"/api/accounts/{a1['id']}")["lifecycleStatus"] == "ACTIVE" and bal(a1["id"]) == 5000)
    check("拒绝后无 CLOSE 留痕（受理前拒绝，不落悬挂单）",
          not any(x["action"] in ("CLOSE", "CLOSE_REQUEST") for x in
                  ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs")))
    # 3.2 无 step-up → 403
    st, resp = close(a1["id"], "m1-nostep", step=False, expect=403)
    check("销户无 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    # 3.3 有金额冻结（A-3）
    fz = ok("POST", f"/api/accounts/{a1['id']}/freeze",
            {"amount": 2000, "reasonType": "RISK", "reason": "A-5 验证", "bizNo": f"alcfz-m1-{SUFFIX}"})
    check("A-3 金额冻结单 ACTIVE", fz["status"] == "ACTIVE", str(fz))
    st, resp = close(a1["id"], "m1-frozen", expect=400)
    check("有 ACTIVE 金额冻结单销户拒绝", st == 400, f"{st} {resp}")
    check("拒绝原因明示未解冻冻结单", "冻结单" in str(resp.get("error", "")), str(resp))
    ok("POST", f"/api/accounts/{a1['id']}/unfreeze",
       {"freezeId": fz["id"], "reasonType": "RISK", "bizNo": f"alcufz-m1-{SUFFIX}"})
    check("解冻后冻结单 RELEASED",
          ok("GET", f"/api/accounts/{a1['id']}/freezes?status=ACTIVE") == [])
    # 3.4 状态冻结（余额归零后仍拒绝）
    m2 = new_member("M2")
    a2 = new_account(m2["id"])
    ok("POST", f"/api/accounts/{a2['id']}/status-ex", {"status": "FROZEN", "freezeType": "FULL", "reason": "A-5 验证"})
    st, resp = close(a2["id"], "m2-statusfrozen", expect=409)
    check("状态冻结户销户拒绝（409，余额为 0 也拒绝）", st == 409, f"{st} {resp}")
    check("拒绝原因明示状态非 ACTIVE", "非 ACTIVE" in str(resp.get("error", "")), str(resp))
    ok("POST", f"/api/accounts/{a2['id']}/status-ex", {"status": "ACTIVE", "freezeType": "NONE"})
    # 3.5 在途提现（余额已为 0，仍拒绝）—— 确定性注入 PENDING 卡
    m3 = new_member("M3")
    a3 = new_account(m3["id"])
    topup(a3["id"], 1000, "m3")
    w_pending = ok("POST", "/api/settle/withdraw",
                   {"accountId": a3["id"], "amount": 1000, "requestId": f"alcwd-pending-{SUFFIX}",
                    "bankCardNo": gen_card() + "PENDING"}, step=True)
    check("PENDING 卡提现受理 PROCESSING（在途）", w_pending["status"] == "PROCESSING", str(w_pending))
    check("在途提现后可用余额已为 0", bal(a3["id"]) == 0, str(bal(a3["id"])))
    expo = ok("GET", f"/api/settle/exposure?memberId={m3['id']}&accountId={a3['id']}")
    check("资金敞口视图：在途提现 1 笔且 clear=false",
          expo["withdrawInFlight"] == 1 and expo["clear"] is False, str(expo))
    st, resp = close(a3["id"], "m3-inflight", expect=400)
    check("余额为 0 但有在途提现 → 销户拒绝", st == 400, f"{st} {resp}")
    check("拒绝原因明示在途提现", "在途提现" in str(resp.get("error", "")), str(resp))
    ok("POST", f"/api/settle/withdraw/{w_pending['id']}/receipt", {"status": "FAILED", "reason": "A-5 验证终态"})
    check("提现失败全额退回（余额复原 1000）", bal(a3["id"]) == 1000, str(bal(a3["id"])))
    expo2 = ok("GET", f"/api/settle/exposure?memberId={m3['id']}&accountId={a3['id']}")
    check("终态后敞口 clear=true", expo2["clear"] is True and expo2["withdrawInFlight"] == 0, str(expo2))
    withdraw_out(a3["id"], 1000, "m3-zero")
    check("出金后余额归零", bal(a3["id"]) == 0)
    close(a3["id"], "m3-ok")
    check("在途收敛 + 余额归零后销户成功",
          ok("GET", f"/api/accounts/{a3['id']}")["lifecycleStatus"] == "CLOSED")

    # ---------------- [4] 销户成功 / 留痕 / 不物理删 / 不可用 / 幂等 ----------------
    print("[4] A-5 销户：状态机 + 留痕 + 不物理删 + 销户后不可用 + 幂等")
    withdraw_out(a1["id"], 5000, "m1-zero")
    check("销户前余额归零", bal(a1["id"]) == 0)
    closed = close(a1["id"], "m1-ok")
    check("销户返回 lifecycleStatus=CLOSED", closed["lifecycleStatus"] == "CLOSED", str(closed))
    check("销户同步 status=CLOSED（账务前置校验天然拒绝）", closed["status"] == "CLOSED", str(closed))
    check("销户 closedAt 已落库", isinstance(closed.get("closedAt"), int) and closed["closedAt"] > 0, str(closed))
    check("销户不动资金（余额仍 0）", bal(a1["id"]) == 0)
    logs = ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs")
    actions = [x["action"] for x in logs]
    check("生命周期状态机留痕 CLOSE_REQUEST→CLOSE",
          "CLOSE_REQUEST" in actions and "CLOSE" in actions
          and actions.index("CLOSE_REQUEST") < actions.index("CLOSE"), str(actions))
    close_row = next(x for x in logs if x["action"] == "CLOSE")
    check("销户留痕含 from/to 状态与操作员",
          close_row["fromStatus"] == "CLOSING" and close_row["toStatus"] == "CLOSED"
          and close_row["operator"] == "admin", str(close_row))
    check("销户留痕含幂等键 bizNo", close_row["bizNo"] == f"alcclose-m1-ok-{SUFFIX}", str(close_row))
    # 不物理删
    st, resp = api("GET", f"/api/accounts/{a1['id']}")
    check("销户后账户行仍可查（**未物理删除**）", st == 200 and resp["id"] == a1["id"], f"{st} {resp}")
    check("销户账户仍在账户列表中（逻辑销户）",
          any(x["id"] == a1["id"] for x in ok("GET", f"/api/accounts?memberId={m1['id']}")))
    st, entries = api("GET", f"/api/accounting/accounts/{a1['id']}/entries?limit=50")
    check("销户后历史分录仍可查（审计可追溯）", st == 200 and len(entries) > 0, f"{st} {len(entries or [])}")
    check("销户后余额行仍在账务域（Σbalance=0 不回归）", bal(a1["id"]) == 0)
    # 销户后不可用
    st, resp = api("POST", "/api/accounting/internal/topup",
                   {"accountId": a1["id"], "amount": 100, "bizNo": f"alc-closed-topup-{SUFFIX}"})
    check("销户后充值被拒绝（账户不可用）", st >= 400, f"{st} {resp}")
    st, resp = api("POST", f"/api/accounts/{a1['id']}/freeze",
                   {"amount": 100, "reasonType": "RISK", "bizNo": f"alc-closed-fz-{SUFFIX}"})
    check("销户后金额冻结被拒绝", st >= 400, f"{st} {resp}")
    st, resp = api("POST", f"/api/accounts/{a1['id']}/status-ex", {"status": "ACTIVE", "freezeType": "NONE"})
    check("销户后不可经状态变更复活（409）", st == 409, f"{st} {resp}")
    st, resp = api("POST", f"/api/accounts/{a1['id']}/level", {"targetLevel": "II"}, step=True)
    check("销户后不可升降级（409）", st == 409, f"{st} {resp}")
    # 幂等
    logs_count = len(ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs"))
    again = close(a1["id"], "m1-ok")
    check("重复销户幂等（同快照、closedAt 不变）",
          again["id"] == closed["id"] and again["closedAt"] == closed["closedAt"], str(again))
    check("重复销户不重复留痕",
          len(ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs")) == logs_count)
    resp = close(a1["id"], "m1-other-requestid", expect=200)
    check("换 requestId 重复销户仍幂等（(ACCOUNT_CLOSE,accountId) 口径）",
          resp["lifecycleStatus"] == "CLOSED" and resp["closedAt"] == closed["closedAt"], str(resp))
    st, resp = api("POST", "/api/accounts/NOT_EXIST/close", {"requestId": f"alc-404-{SUFFIX}"}, step=True)
    check("不存在账户销户 404", st == 404, f"{st} {resp}")

    # ---------------- [5] 升降级状态机 + KYC 联动 ----------------
    print("[5] A-5 升降级：状态机 + KYC 联动 + 限额联动 + step-up")
    m4 = new_member("M4", "USER")
    a4 = new_account(m4["id"])
    lv0 = ok("GET", f"/api/accounts/{a4['id']}/level")
    check("新户 I 类限额（单笔 1000 万分）", lv0["level"] == "I" and lv0["singleLimit"] == 10_000_000, str(lv0))
    st, resp = api("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "II"})
    check("未实名升级 II 被拒绝（KYC 联动）", st >= 400, f"{st} {resp}")
    check("拒绝原因明示 KYC", "KYC" in str(resp.get("error", "")), str(resp))
    kyc = ok("POST", f"/api/members/{m4['id']}/kyc",
             {"name": "张三", "idCardNo": gen_id_card(), "bankCardNo": gen_card(), "mobile": gen_mobile()})
    check("KYC 四要素 PASS（等级 II）", kyc.get("pass") is True and kyc.get("level") == "II", str(kyc))
    up = ok("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "II", "reason": "A-5 升级"})
    check("实名后升级 II 成功", up["level"] == "II", str(up))
    lv2 = ok("GET", f"/api/accounts/{a4['id']}/level")
    check("升级后限额联动上调（单笔 5000 万分）", lv2["singleLimit"] == 50_000_000, str(lv2))
    logs4 = ok("GET", f"/api/accounts/{a4['id']}/lifecycle-logs")
    up_row = next((x for x in logs4 if x["action"] == "UPGRADE"), None)
    check("升级留痕 from I → to II", up_row and up_row["fromLevel"] == "I" and up_row["toLevel"] == "II", str(up_row))
    st, resp = api("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "I"}, step=False)
    check("降级无 X-Step-Up-Token → 403（敏感操作）", st == 403, f"{st} {resp}")
    down = ok("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "I", "reason": "A-5 降级"}, step=True)
    check("降级 I 成功（状态机可逆）", down["level"] == "I", str(down))
    lv1 = ok("GET", f"/api/accounts/{a4['id']}/level")
    check("降级后限额联动下调（单笔回到 1000 万分）", lv1["singleLimit"] == 10_000_000, str(lv1))
    logs4 = ok("GET", f"/api/accounts/{a4['id']}/lifecycle-logs")
    dn_row = next((x for x in logs4 if x["action"] == "DOWNGRADE"), None)
    check("降级留痕 from II → to I + operator", dn_row and dn_row["fromLevel"] == "II"
          and dn_row["toLevel"] == "I" and dn_row["operator"] == "admin", str(dn_row))
    same = ok("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "I"}, step=True)
    check("目标=当前等级幂等返回", same["level"] == "I")
    check("幂等不重复留痕",
          len([x for x in ok("GET", f"/api/accounts/{a4['id']}/lifecycle-logs") if x["action"] == "DOWNGRADE"]) == 1)
    st, resp = api("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "IV"}, step=True)
    check("非法等级 400", st == 400, f"{st} {resp}")
    # 降级余额护栏：III 类户余额超 I 类上限 → 降级拒绝
    ok("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "III", "reason": "A-5 升级 III"})
    check("III 类升级成功（KYC PASS + operator 复核）",
          ok("GET", f"/api/accounts/{a4['id']}")["level"] == "III")
    topup(a4["id"], 100_000_001, "m4-big")
    st, resp = api("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "I", "reason": "余额超限"}, step=True)
    check("降级后余额超目标等级上限 → 拒绝", st >= 400, f"{st} {resp}")
    check("拒绝原因明示超目标等级上限", "超目标等级上限" in str(resp.get("error", "")), str(resp))
    check("降级拒绝后等级不变（仍 III）", ok("GET", f"/api/accounts/{a4['id']}")["level"] == "III")
    st, resp = close(a4["id"], "m4-bigbalance", expect=400)
    check("大额余额户销户拒绝", st == 400, f"{st} {resp}")
    withdraw_out(a4["id"], 100_000_001, "m4-zero")
    down2 = ok("POST", f"/api/accounts/{a4['id']}/level", {"targetLevel": "I", "reason": "余额归零后降级"}, step=True)
    check("余额归零后降级成功", down2["level"] == "I", str(down2))
    close(a4["id"], "m4-ok")
    check("降级后销户成功", ok("GET", f"/api/accounts/{a4['id']}")["lifecycleStatus"] == "CLOSED")

    # ---------------- [6] ST-6 保证金 ----------------
    print("[6] ST-6 保证金：缴存/释放/扣划守恒 + 幂等 + 超额拒绝 + step-up")
    dm = new_member("DEP1", prefix="DEPTEST")
    dep_acc = ok("POST", "/api/accounts/typed", {"memberId": dm["id"], "accountType": "DEPOSIT"})
    topup(dep_acc["id"], 10_000, "dep1")
    dep_base = bal("SYS_DEPOSIT")
    fee_base = bal("SYS_FEE")
    d1 = ok("POST", "/api/settle/deposit",
            {"merchantId": dm["id"], "amount": 6000, "requestId": f"dep-freeze-1-{SUFFIX}", "reason": "ST-6 缴存"})
    check("缴存单 type=DEPOSIT status=FROZEN", d1["type"] == "DEPOSIT" and d1["status"] == "FROZEN", str(d1))
    check("缴存出资账户缺省 DEPOSIT 专户（A-1 复用）", d1["accountId"] == dep_acc["id"], str(d1))
    check("缴存复式记账：出资户 −6000", bal(dep_acc["id"]) == 4000, str(bal(dep_acc["id"])))
    check("缴存复式记账：SYS_DEPOSIT +6000", bal("SYS_DEPOSIT") == dep_base + 6000, str(bal("SYS_DEPOSIT")))
    check("缴存账务 bizNo 确定性派生 DP:<requestId>", d1["bizNo"] == f"DP:dep-freeze-1-{SUFFIX}", str(d1))
    v = ok("GET", f"/api/accounting/transfers/{d1['bizNo']}")
    check("缴存凭证双腿 D 出资户 / C SYS_DEPOSIT",
          sorted((l["accountId"], l["dir"], l["amount"]) for l in v["lines"])
          == sorted([(dep_acc["id"], "D", 6000), ("SYS_DEPOSIT", "C", 6000)]), str(v["lines"]))
    d1_again = ok("POST", "/api/settle/deposit",
                  {"merchantId": dm["id"], "amount": 6000, "requestId": f"dep-freeze-1-{SUFFIX}"})
    check("缴存幂等（同 requestId 同 id）", d1_again["id"] == d1["id"], str(d1_again))
    check("缴存幂等不重复动账", bal(dep_acc["id"]) == 4000 and bal("SYS_DEPOSIT") == dep_base + 6000)
    ds = ok("GET", f"/api/settle/deposit/summary?merchantId={dm['id']}")
    check("保证金视图 held=6000", ds["heldAmount"] == 6000 and ds["depositedAmount"] == 6000, str(ds))
    check("保证金恒等式 SYS_DEPOSIT == 全局未释放（误差 0）",
          ds["balanced"] is True and ds["diff"] == 0, str(ds))
    # 保证金敞口阻断销户
    expo_dep = ok("GET", f"/api/settle/exposure?memberId={dm['id']}&accountId={dep_acc['id']}")
    check("保证金敞口 depositHeld=6000 且 clear=false",
          expo_dep["depositHeld"] == 6000 and expo_dep["clear"] is False, str(expo_dep))
    st, resp = close(dep_acc["id"], "dep-held", expect=400)
    check("有未释放保证金 → 销户拒绝（余额为 4000 也拒绝）", st == 400, f"{st} {resp}")
    # 释放（敏感）
    st, resp = api("POST", "/api/settle/deposit/release",
                   {"merchantId": dm["id"], "depositId": d1["id"], "amount": 2000,
                    "requestId": f"dep-rel-nostep-{SUFFIX}"}, step=False)
    check("释放无 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    r1 = ok("POST", "/api/settle/deposit/release",
            {"merchantId": dm["id"], "depositId": d1["id"], "amount": 2000,
             "requestId": f"dep-rel-1-{SUFFIX}"}, step=True)
    check("释放单 type=RELEASE refId=原缴存单", r1["type"] == "RELEASE" and r1["refId"] == d1["id"], str(r1))
    check("释放复式记账：SYS_DEPOSIT −2000 / 原出资户 +2000",
          bal("SYS_DEPOSIT") == dep_base + 4000 and bal(dep_acc["id"]) == 6000,
          f'{bal("SYS_DEPOSIT")} {bal(dep_acc["id"])}')
    d1_after = ok("GET", f"/api/settle/deposits/{d1['id']}")
    check("部分释放后缴存单 status=PARTIAL released=2000",
          d1_after["status"] == "PARTIAL" and d1_after["releasedAmount"] == 2000, str(d1_after))
    r1_again = ok("POST", "/api/settle/deposit/release",
                  {"merchantId": dm["id"], "depositId": d1["id"], "amount": 2000,
                   "requestId": f"dep-rel-1-{SUFFIX}"}, step=True)
    check("释放幂等（同 requestId 同 id）", r1_again["id"] == r1["id"], str(r1_again))
    check("释放幂等不重复动账", bal(dep_acc["id"]) == 6000 and bal("SYS_DEPOSIT") == dep_base + 4000)
    st, resp = api("POST", "/api/settle/deposit/release",
                   {"merchantId": dm["id"], "depositId": d1["id"], "amount": 5000,
                    "requestId": f"dep-rel-over-{SUFFIX}"}, step=True)
    check("超额释放拒绝（剩余 4000 < 5000）", st >= 400, f"{st} {resp}")
    check("超额释放拒绝后余额不变（绝不动账）",
          bal(dep_acc["id"]) == 6000 and bal("SYS_DEPOSIT") == dep_base + 4000)
    # 扣划（敏感）
    st, resp = api("POST", "/api/settle/deposit/deduct",
                   {"merchantId": dm["id"], "depositId": d1["id"], "amount": 1000,
                    "requestId": f"dep-ded-nostep-{SUFFIX}"}, step=False)
    check("扣划无 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    dd = ok("POST", "/api/settle/deposit/deduct",
            {"merchantId": dm["id"], "depositId": d1["id"], "amount": 1000,
             "requestId": f"dep-ded-1-{SUFFIX}", "reason": "违约扣划"}, step=True)
    check("扣划单 type=DEDUCT 去向 SYS_FEE（复用既有平台收入户）",
          dd["type"] == "DEDUCT" and dd["targetAccount"] == "SYS_FEE", str(dd))
    check("扣划复式记账：SYS_DEPOSIT −1000 / SYS_FEE +1000",
          bal("SYS_DEPOSIT") == dep_base + 3000 and bal("SYS_FEE") == fee_base + 1000,
          f'{bal("SYS_DEPOSIT")} {bal("SYS_FEE")}')
    check("扣划不动商户余额（保证金已缴存在平台）", bal(dep_acc["id"]) == 6000)
    dd_again = ok("POST", "/api/settle/deposit/deduct",
                  {"merchantId": dm["id"], "depositId": d1["id"], "amount": 1000,
                   "requestId": f"dep-ded-1-{SUFFIX}"}, step=True)
    check("扣划幂等（同 requestId 同 id）", dd_again["id"] == dd["id"], str(dd_again))
    check("扣划幂等不重复动账", bal("SYS_FEE") == fee_base + 1000)
    st, resp = api("POST", "/api/settle/deposit/deduct",
                   {"merchantId": dm["id"], "depositId": d1["id"], "amount": 1000,
                    "targetAccount": dep_acc["id"], "requestId": f"dep-ded-badtarget-{SUFFIX}"}, step=True)
    check("扣划去向非系统户拒绝（防资金 redirect）", st >= 400, f"{st} {resp}")
    # 释放剩余 → 缴存单终态
    r2 = ok("POST", "/api/settle/deposit/release",
            {"merchantId": dm["id"], "depositId": d1["id"], "amount": 3000,
             "requestId": f"dep-rel-2-{SUFFIX}"}, step=True)
    d1_final = ok("GET", f"/api/settle/deposits/{d1['id']}")
    check("释放+扣划耗尽后缴存单 status=MIXED（released 5000 / deducted 1000）",
          d1_final["status"] == "MIXED" and d1_final["releasedAmount"] == 5000
          and d1_final["deductedAmount"] == 1000, str(d1_final))
    ds2 = ok("GET", f"/api/settle/deposit/summary?merchantId={dm['id']}")
    check("保证金守恒：held=0 且恒等式仍成立",
          ds2["heldAmount"] == 0 and ds2["balanced"] is True and ds2["diff"] == 0, str(ds2))
    check("SYS_DEPOSIT 回到基线（缴存全额释放/扣划）", bal("SYS_DEPOSIT") == dep_base, str(bal("SYS_DEPOSIT")))
    check("保证金单据可列举（DEPOSIT/RELEASE/DEDUCT 三类齐全）",
          {x["type"] for x in ok("GET", f"/api/settle/deposits?merchantId={dm['id']}")}
          == {"DEPOSIT", "RELEASE", "DEDUCT"})
    expo_dep2 = ok("GET", f"/api/settle/exposure?memberId={dm['id']}&accountId={dep_acc['id']}")
    check("保证金清零后敞口 clear=true", expo_dep2["clear"] is True, str(expo_dep2))
    trial_guard("保证金后")

    # ---------------- [7] ST-6 授信/垫资/追偿 ----------------
    print("[7] ST-6 垫资：授信额度内垫付/追偿 + 并发不超授信 + 幂等 + 恒等式")
    cm = new_member("CR1", prefix="DEPTEST")
    ca = new_account(cm["id"])
    adv_base = bal("SYS_ADVANCE")
    st, resp = api("POST", "/api/settle/credit",
                   {"merchantId": cm["id"], "creditLimit": 5000}, step=False)
    check("授信无 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    cr = ok("POST", "/api/settle/credit",
            {"merchantId": cm["id"], "creditLimit": 5000, "status": "ACTIVE"}, step=True)
    check("授信开立 limit=5000 used=0", cr["creditLimit"] == 5000 and cr["used"] == 0, str(cr))
    nm = new_member("CR2", prefix="DEPTEST")
    new_account(nm["id"])
    st, resp = api("POST", "/api/settle/advance",
                   {"merchantId": nm["id"], "amount": 1000, "requestId": f"adv-nocredit2-{SUFFIX}"})
    check("未授信商户垫资拒绝（无额度）", st >= 400, f"{st} {resp}")
    adv1 = ok("POST", "/api/settle/advance",
              {"merchantId": cm["id"], "amount": 3000, "requestId": f"adv-1-{SUFFIX}", "reason": "ST-6 垫资"})
    check("垫资单 type=ADVANCE status=ADVANCED", adv1["type"] == "ADVANCE" and adv1["status"] == "ADVANCED", str(adv1))
    check("垫资入账户缺省商户 BALANCE 户", adv1["accountId"] == ca["id"], str(adv1))
    check("垫资复式记账：商户 +3000", bal(ca["id"]) == 3000, str(bal(ca["id"])))
    check("垫资复式记账：SYS_ADVANCE −3000（应收资产，借增）",
          bal("SYS_ADVANCE") == adv_base - 3000, str(bal("SYS_ADVANCE")))
    check("垫资凭证双腿 D SYS_ADVANCE / C 商户",
          sorted((l["accountId"], l["dir"], l["amount"]) for l in
                 ok("GET", f"/api/accounting/transfers/{adv1['bizNo']}")["lines"])
          == sorted([("SYS_ADVANCE", "D", 3000), (ca["id"], "C", 3000)]))
    cr1 = ok("GET", f"/api/settle/credit?merchantId={cm['id']}")
    check("垫资后额度原子占用 used=3000", cr1["used"] == 3000, str(cr1))
    adv1_again = ok("POST", "/api/settle/advance",
                    {"merchantId": cm["id"], "amount": 3000, "requestId": f"adv-1-{SUFFIX}"})
    check("垫资幂等（同 requestId 同 id）", adv1_again["id"] == adv1["id"], str(adv1_again))
    check("垫资幂等不重复动账/不重复占额度",
          bal(ca["id"]) == 3000 and ok("GET", f"/api/settle/credit?merchantId={cm['id']}")["used"] == 3000)
    st, resp = api("POST", "/api/settle/advance",
                   {"merchantId": cm["id"], "amount": 3000, "requestId": f"adv-over-{SUFFIX}"})
    check("超授信垫资拒绝（used 3000 + 3000 > 5000）", st >= 400, f"{st} {resp}")
    check("超授信拒绝后余额与额度不变（绝不动账、不落悬挂单）",
          bal(ca["id"]) == 3000 and ok("GET", f"/api/settle/credit?merchantId={cm['id']}")["used"] == 3000)
    check("超授信拒绝无垫资单落库",
          not any(x["requestId"] == f"adv-over-{SUFFIX}" for x in
                  ok("GET", f"/api/settle/advances?merchantId={cm['id']}")))
    cs = ok("GET", f"/api/settle/credit/summary?merchantId={cm['id']}")
    check("垫资守恒 used == outstanding == −SYS_ADVANCE（全局）",
          cs["usedMatched"] is True and cs["balanced"] is True and cs["diff"] == 0
          and cs["outstanding"] == 3000, str(cs))
    # 垫资敞口阻断销户（先把商户余额出金归零）
    withdraw_out(ca["id"], 3000, "cr1-zero")
    check("垫资户余额归零后仍不可销户", bal(ca["id"]) == 0)
    expo_adv = ok("GET", f"/api/settle/exposure?memberId={cm['id']}&accountId={ca['id']}")
    check("垫资敞口 advanceOutstanding=3000 clear=false",
          expo_adv["advanceOutstanding"] == 3000 and expo_adv["clear"] is False, str(expo_adv))
    st, resp = close(ca["id"], "cr1-outstanding", expect=400)
    check("余额为 0 但有未回收垫资 → 销户拒绝", st == 400, f"{st} {resp}")
    check("拒绝原因明示未回收垫资", "垫资" in str(resp.get("error", "")), str(resp))
    # 并发不超授信（剩余额度 2000，5 笔各 1000 并发 ⇒ 最多 2 笔成功）
    def concurrent_adv(i):
        return api("POST", "/api/settle/advance",
                   {"merchantId": cm["id"], "amount": 1000, "requestId": f"adv-conc-{i}-{SUFFIX}"})
    with ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(concurrent_adv, range(5)))
    success = [r for r in results if r[0] == 200]
    check("并发垫资不超授信（剩余 2000 ⇒ 恰好 2 笔成功）", len(success) == 2,
          str([(r[0], str(r[1])[:80]) for r in results]))
    cr2 = ok("GET", f"/api/settle/credit?merchantId={cm['id']}")
    check("并发后 used=5000（== limit，未超额）", cr2["used"] == 5000, str(cr2))
    cs2 = ok("GET", f"/api/settle/credit/summary?merchantId={cm['id']}")
    check("并发后守恒仍成立（used == outstanding == −SYS_ADVANCE）",
          cs2["usedMatched"] is True and cs2["balanced"] is True and cs2["outstanding"] == 5000, str(cs2))
    check("并发后商户余额 = Σ成功垫资额（2×1000）", bal(ca["id"]) == 2000, str(bal(ca["id"])))
    # 追偿（敏感）：先补足商户余额，保证追偿可扣（余额不足时 post 会拒绝）
    topup(ca["id"], 8000, "cr1-fund")
    check("追偿前商户余额充足（10000）", bal(ca["id"]) == 10000, str(bal(ca["id"])))
    st, resp = api("POST", "/api/settle/advance/recover",
                   {"merchantId": cm["id"], "advanceId": adv1["id"], "amount": 1000,
                    "requestId": f"adv-rec-nostep-{SUFFIX}"}, step=False)
    check("追偿无 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    rec = ok("POST", "/api/settle/advance/recover",
             {"merchantId": cm["id"], "advanceId": adv1["id"], "amount": 1000,
              "requestId": f"adv-rec-1-{SUFFIX}"}, step=True)
    check("追偿后垫资单 status=PARTIAL recovered=1000",
          rec["status"] == "PARTIAL" and rec["recoveredAmount"] == 1000, str(rec))
    check("追偿复式记账：商户 −1000 / SYS_ADVANCE +1000",
          bal(ca["id"]) == 9000 and bal("SYS_ADVANCE") == adv_base - 4000,
          f'{bal(ca["id"])} {bal("SYS_ADVANCE")}')
    check("追偿后额度释放 used=4000",
          ok("GET", f"/api/settle/credit?merchantId={cm['id']}")["used"] == 4000)
    rec_again = ok("POST", "/api/settle/advance/recover",
                   {"merchantId": cm["id"], "advanceId": adv1["id"], "amount": 1000,
                    "requestId": f"adv-rec-1-{SUFFIX}"}, step=True)
    check("追偿幂等（同 requestId 不重复动账）",
          rec_again["recoveredAmount"] == 1000 and bal(ca["id"]) == 9000, str(rec_again))
    st, resp = api("POST", "/api/settle/advance/recover",
                   {"merchantId": cm["id"], "advanceId": adv1["id"], "amount": 5000,
                    "requestId": f"adv-rec-over-{SUFFIX}"}, step=True)
    check("超额追偿拒绝（未回收 2000 < 5000）", st >= 400, f"{st} {resp}")
    check("超额追偿拒绝后余额不变", bal(ca["id"]) == 9000)
    rec2 = ok("POST", "/api/settle/advance/recover",
              {"merchantId": cm["id"], "advanceId": adv1["id"], "amount": 2000,
               "requestId": f"adv-rec-2-{SUFFIX}"}, step=True)
    check("全额追偿后垫资单 status=RECOVERED", rec2["status"] == "RECOVERED", str(rec2))
    check("追偿留痕行 type=RECOVER 可查",
          any(x["type"] == "RECOVER" and x["refId"] == adv1["id"] for x in
              ok("GET", f"/api/settle/advances?merchantId={cm['id']}")))
    st, resp = api("POST", "/api/settle/credit",
                   {"merchantId": cm["id"], "creditLimit": 100}, step=True)
    check("授信额度不得低于已占用（拒绝）", st >= 400, f"{st} {resp}")
    frozen_cr = ok("POST", "/api/settle/credit",
                   {"merchantId": cm["id"], "creditLimit": 5000, "status": "FROZEN"}, step=True)
    check("授信可冻结（status=FROZEN）", frozen_cr["status"] == "FROZEN", str(frozen_cr))
    st, resp = api("POST", "/api/settle/advance",
                   {"merchantId": cm["id"], "amount": 100, "requestId": f"adv-frozen-{SUFFIX}"})
    check("授信冻结后禁止新垫资", st >= 400, f"{st} {resp}")
    ok("POST", "/api/settle/credit", {"merchantId": cm["id"], "creditLimit": 5000, "status": "ACTIVE"}, step=True)
    trial_guard("垫资后")

    # ---------------- [8] 保证金扣划偿还垫资 ----------------
    print("[8] ST-6 联动：保证金扣划偿还垫资（D SYS_DEPOSIT / C SYS_ADVANCE）")
    adv2 = ok("POST", "/api/settle/advance",
              {"merchantId": cm["id"], "amount": 2000, "requestId": f"adv-2-{SUFFIX}"})
    check("新垫资 2000 成功（额度已释放）", adv2["amount"] == 2000, str(adv2))
    dep2 = ok("POST", "/api/settle/deposit",
              {"merchantId": cm["id"], "accountId": ca["id"], "amount": 2000,
               "requestId": f"dep-freeze-2-{SUFFIX}"})
    check("保证金缴存（显式 BALANCE 出资户）成功", dep2["accountId"] == ca["id"], str(dep2))
    dep_before = bal("SYS_DEPOSIT")
    adv_before = bal("SYS_ADVANCE")
    used_before = ok("GET", f"/api/settle/credit?merchantId={cm['id']}")["used"]
    dd2 = ok("POST", "/api/settle/deposit/deduct",
             {"merchantId": cm["id"], "depositId": dep2["id"], "amount": 2000, "advanceId": adv2["id"],
              "requestId": f"dep-ded-adv-{SUFFIX}", "reason": "保证金偿还垫资"}, step=True)
    check("扣划偿还垫资去向 SYS_ADVANCE", dd2["targetAccount"] == "SYS_ADVANCE", str(dd2))
    check("联动复式记账：SYS_DEPOSIT −2000 / SYS_ADVANCE +2000",
          bal("SYS_DEPOSIT") == dep_before - 2000 and bal("SYS_ADVANCE") == adv_before + 2000,
          f'{bal("SYS_DEPOSIT")} {bal("SYS_ADVANCE")}')
    adv2_after = ok("GET", f"/api/settle/advances/{adv2['id']}")
    check("垫资单被保证金扣划全额回收（RECOVERED）",
          adv2_after["status"] == "RECOVERED" and adv2_after["recoveredAmount"] == 2000, str(adv2_after))
    check("联动后额度释放（used 回到扣划前 −2000）",
          ok("GET", f"/api/settle/credit?merchantId={cm['id']}")["used"] == used_before - 2000)
    cs3 = ok("GET", f"/api/settle/credit/summary?merchantId={cm['id']}")
    ds3 = ok("GET", f"/api/settle/deposit/summary?merchantId={cm['id']}")
    check("联动后保证金/垫资双恒等式仍成立",
          cs3["balanced"] is True and cs3["usedMatched"] is True and ds3["balanced"] is True,
          f"{cs3} {ds3}")
    check("联动后商户保证金敞口为 0", ds3["heldAmount"] == 0, str(ds3))
    # 收尾：把并发垫资剩余追偿干净，验证敞口清零后可销户
    remaining_adv = [x for x in ok("GET", f"/api/settle/advances?merchantId={cm['id']}&type=ADVANCE")
                     if x["amount"] > x["recoveredAmount"]]
    check("收尾前仍有 2 笔并发垫资未回收", len(remaining_adv) == 2, str(len(remaining_adv)))
    for adv in remaining_adv:
        ok("POST", "/api/settle/advance/recover",
           {"merchantId": cm["id"], "advanceId": adv["id"], "amount": adv["amount"] - adv["recoveredAmount"],
            "requestId": f"adv-final-{adv['id']}-{SUFFIX}"}, step=True)
    cs4 = ok("GET", f"/api/settle/credit/summary?merchantId={cm['id']}")
    check("全部追偿后 outstanding=0 且 used=0", cs4["outstanding"] == 0 and cs4["used"] == 0, str(cs4))
    check("全部追偿后 SYS_ADVANCE 回到基线", bal("SYS_ADVANCE") == adv_base, str(bal("SYS_ADVANCE")))
    final_balance = bal(ca["id"])
    if final_balance > 0:
        withdraw_out(ca["id"], final_balance, "cr1-final")
    check("出金后垫资户余额归零", bal(ca["id"]) == 0, str(bal(ca["id"])))
    expo_final = ok("GET", f"/api/settle/exposure?memberId={cm['id']}&accountId={ca['id']}")
    check("敞口全清 clear=true", expo_final["clear"] is True, str(expo_final))
    close(ca["id"], "cr1-ok")
    check("敞口清零 + 余额归零后销户成功",
          ok("GET", f"/api/accounts/{ca['id']}")["lifecycleStatus"] == "CLOSED")

    # ---------------- [9] 资金护航 + ride 兼容 ----------------
    print("[9] 资金护航：试算平衡（含 byCurrency）+ ride 既有账户不受影响")
    trial_guard("收尾")
    t = trial_all()
    check("trial 顶层仍 = CNY 账套（既有口径不变）",
          (t["totalDebit"], t["totalCredit"], t["balanceSum"]) ==
          (next(r for r in t["byCurrency"] if r["currency"] == "CNY")["totalDebit"],
           next(r for r in t["byCurrency"] if r["currency"] == "CNY")["totalCredit"],
           next(r for r in t["byCurrency"] if r["currency"] == "CNY")["balanceSum"]), str(t)[:200])
    check("SYS_DEPOSIT 科目 = 2006 保证金（复用既有科目，未新增）",
          str(ok("GET", "/api/accounting/accounts/SYS_DEPOSIT/subject")) == "2006")
    check("保证金全局恒等式收尾成立",
          ok("GET", "/api/settle/deposit/summary")["balanced"] is True)
    unchanged = 0
    for aid, before in ride_snapshot.items():
        after = snapshot_account(aid)
        if after == before:
            unchanged += 1
        else:
            check(f"ride/既有账户 {aid} 不受影响", False, f"{before} → {after}")
    check(f"ride/既有账户 {len(ride_snapshot)} 户逐字段不变（状态/等级/类型/币种/生命周期/余额）",
          unchanged == len(ride_snapshot), f"{unchanged}/{len(ride_snapshot)}")
    check("既有账户余额合计不变", sum(snapshot_account(a)["balance"] for a in ride_snapshot) == ride_balance_sum)
    check("既有账户无一被销户",
          all(snapshot_account(a)["lifecycleStatus"] == "ACTIVE" for a in ride_snapshot))
    now_accounts = ok("GET", "/api/accounts")
    check("账户总数只增不减（销户不物理删）", len(now_accounts) >= accounts_before,
          f"{accounts_before} → {len(now_accounts)}")
    closed_ids = [a1["id"], a3["id"], a4["id"], ca["id"]]
    check("本次销户账户仍全部在列表内（逻辑销户）",
          all(any(x["id"] == cid for x in now_accounts) for cid in closed_ids))

    # ---------------- [10] 分片/单库两态一致 ----------------
    print("[10] 分片/单库两态结果一致（一键回滚再切回）")
    def dep_snapshot():
        return {
            "deposits": ok("GET", f"/api/settle/deposits?merchantId={dm['id']}"),
            "depSummary": ok("GET", f"/api/settle/deposit/summary?merchantId={dm['id']}"),
            "advances": ok("GET", f"/api/settle/advances?merchantId={cm['id']}"),
            "credit": ok("GET", f"/api/settle/credit?merchantId={cm['id']}"),
            "creditSummary": ok("GET", f"/api/settle/credit/summary?merchantId={cm['id']}"),
            "exposure": ok("GET", f"/api/settle/exposure?memberId={cm['id']}&accountId={ca['id']}"),
            "account": ok("GET", f"/api/accounts/{a1['id']}"),
            "logs": ok("GET", f"/api/accounts/{a1['id']}/lifecycle-logs"),
            "balances": {x: bal(x) for x in (dep_acc["id"], ca["id"], "SYS_DEPOSIT", "SYS_ADVANCE")},
        }
    shard_state = act("ppshard")
    if shard_state.get("shardingConfigured"):
        # 单库态写测试用独立商户，避免污染 dm/cm 的对照快照
        dm2 = new_member("DEP2", prefix="DEPTEST")
        dm2_acc = ok("POST", "/api/accounts/typed", {"memberId": dm2["id"], "accountType": "DEPOSIT"})
        topup(dm2_acc["id"], 1000, "dep2")
        before = dep_snapshot()
        off = act("ppshard", {"action": "toggle", "enabled": False})
        check("一键回滚到单库", off["shardingActive"] is False, str(off))
        after = dep_snapshot()
        for key in before:
            check(f"单库态 {key} 与分片态逐条一致", after[key] == before[key], f"{key} diff")
        d_single = ok("POST", "/api/settle/deposit",
                      {"merchantId": dm2["id"], "amount": 1000, "requestId": f"dep-single-{SUFFIX}"})
        check("单库态缴存仍成功且守恒",
              ok("GET", f"/api/settle/deposit/summary?merchantId={dm2['id']}")["heldAmount"] == 1000
              and ok("GET", "/api/settle/deposit/summary")["balanced"] is True, str(d_single))
        ok("POST", "/api/settle/deposit/release",
           {"merchantId": dm2["id"], "depositId": d_single["id"], "amount": 1000,
            "requestId": f"dep-single-rel-{SUFFIX}"}, step=True)
        check("单库态释放后全局恒等式仍成立",
              ok("GET", "/api/settle/deposit/summary")["balanced"] is True)
        on = act("ppshard", {"action": "toggle", "enabled": True})
        check("切回分片态", on["shardingActive"] is True, str(on))
        again = dep_snapshot()
        for key in before:
            check(f"切回分片后 {key} 仍一致", again[key] == before[key], f"{key} diff after re-enable")
        check("影子写失败计数为 0", act("ppshard").get("mirrorFailures", 0) == 0,
              str(act("ppshard").get("mirrorFailures")))
        trial_guard("分片回滚往返后")
    else:
        check("分片未装配（local 单库）跳过分片对照", True)

    print()
    print(f"结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - " + f)
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as e:
        print(f"\n断言中止：{e}")
        print(f"结果：{PASSED} 通过，{FAILED} 失败（中止）")
        sys.exit(1)