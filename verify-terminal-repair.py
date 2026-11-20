#!/usr/bin/env python3
"""verify-terminal-repair.py — 受控终态修复能力验证（#106，用户拍板①）

背景：历史脏单 P360 因 TCC confirm/cancel 竞态（根因已用互斥 CAS 修死）落成
      「dtx=CONFIRMED + 账务三腿齐全 + 订单 PAID，但支付单 FAILED」。
决策：依权威 dtx 状态修正为 SUCCESS（不红冲退款，避免资金退回但订单仍 PAID 的新不一致）。

端点契约（payment 域 additive）：
  POST /api/payments/{id}/repair-terminal   body={reason, requestId}
    → 200 {id, before, after, authorityXid, voucherBizNo, repaired, reviewId?}
    → 403 A0403  缺 X-Step-Up-Token（敏感操作）
    → 400 A0428  大额未复核（REVIEW_PENDING，返回 reviewId）
    → 409 A0409  前置校验不满足（非 FAILED / dtx 非 CONFIRMED / 凭证缺失或金额不符 /
                 订单非 PAID 或不指向本单 / 已有红冲凭证 / 已 SUCCESS 重复修复）
    → 404 A0404  支付单不存在

护栏：step-up 绑会话用户自己的口令；金额 ≥ 阈值走双人复核（submitter≠approver）；
      CAS `WHERE id=? AND status='FAILED'` 只翻一次；幂等；全程审计；**不动任何资金**。

跑法：python3 verify-terminal-repair.py            （栈需已启动，默认 docker）
      PP_REPAIR_TARGET=P360 可指定目标单（默认自动挑一笔符合前置条件的脏单）
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
SUFFIX = uuid.uuid4().hex[:8]
ADMIN_PW = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
PASSED = FAILED = SKIPPED = 0
TOKEN = None
STEP = None
STEP_EXP = 0.0


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        print(f"  [FAIL] {name}  {detail}")


def skip(name, why):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {name}  ({why})")


def http(method, path, body=None, bearer=None, step=None, timeout=40):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if bearer:
        r.add_header("Authorization", "Bearer " + bearer)
    if step:
        r.add_header("X-Step-Up-Token", step)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            t = x.read().decode()
            return x.status, json.loads(t) if t else None
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, {"error": t}
    except Exception as e:
        return 0, {"error": str(e)}


def login(user, pw):
    st, r = http("POST", "/api/auth/login", {"username": user, "password": pw})
    assert st == 200 and r.get("token"), f"登录失败 {user}: {st} {r}"
    return r["token"]


def step_up(bearer, pw):
    st, r = http("POST", "/api/security/step-up", {"password": pw}, bearer=bearer)
    assert st == 200 and r.get("token"), f"二次认证失败: {st} {r}"
    return r["token"], time.time() + max(10, (r.get("expiresIn") or 300) - 10)


def api(method, path, body=None, step=False, bearer=None):
    global STEP, STEP_EXP
    tk = bearer or TOKEN
    s = None
    if step:
        if not STEP or time.time() >= STEP_EXP:
            STEP, STEP_EXP = step_up(tk, ADMIN_PW if tk == TOKEN else BOB_PW)
        s = STEP
    return http(method, path, body, bearer=tk, step=s)


def ok(method, path, body=None, step=False):
    st, r = api(method, path, body, step=step)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {r}"
    return r


def bal(aid):
    return ok("GET", f"/api/accounting/accounts/{aid}/balance")


def trial():
    return ok("GET", "/api/accounting/trial")


def entries(biz_no):
    r = ok("GET", f"/api/accounting/entries?bizNo={biz_no}&size=200")
    return r.get("items") or []


def debit_sum(items):
    return sum(x["amount"] for x in items if x.get("dir") == "D")


def recon():
    return ok("POST", "/api/recon/run", {})


def semantic_violations():
    """从对账 detail 里解析「FAILED 单已动账未冲正」计数（主会话补的盲区校验）。"""
    rep = recon()
    n = 0
    for c in rep.get("checks", []):
        d = str(c.get("detail") or "")
        if "FAILED 单已动账未冲正" in d:
            import re
            m = re.search(r"另有 (\d+) 笔 FAILED 单已动账未冲正", d)
            if m:
                n += int(m.group(1))
    return rep, n


BOB_PW = "Repair#2026x"


def main():
    global TOKEN, STEP, STEP_EXP
    print(f"== 受控终态修复验证（批次 {SUFFIX}）==")
    TOKEN = login("admin", ADMIN_PW)
    STEP, STEP_EXP = step_up(TOKEN, ADMIN_PW)

    # 端点存在性探测（未实现则整段 SKIP 并明确说明，不伪造绿）
    st, probe = api("POST", "/api/payments/PROBE-ABSENT/repair-terminal",
                    {"reason": "probe", "requestId": f"probe-{SUFFIX}"}, step=True)
    if st == 404 and isinstance(probe, dict) and probe.get("code") == "A0404":
        print("  端点已就位（不存在单返回 A0404）")
    elif st == 404:
        skip("全部", "POST /api/payments/{id}/repair-terminal 尚未实现（404 非 A0404），待 payment 域交棒后实施")
        print(f"\n结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过")
        return 1
    else:
        print(f"  端点探测返回 {st}（继续）")

    print("== 1. 闸门与前置校验 ==")
    target = os.environ.get("PP_REPAIR_TARGET", "P360")
    st, p = api("GET", f"/api/payments/{target}")
    if st != 200:
        skip("全部", f"目标单 {target} 不存在")
        print(f"\n结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过")
        return 1
    check("目标单存在且为 FAILED", p.get("status") == "FAILED", str(p.get("status")))
    xid = p.get("xid")
    check("目标单 xid 非空", bool(xid), str(xid))

    # 无 step-up → 403
    st, r = http("POST", f"/api/payments/{target}/repair-terminal",
                 {"reason": "无 token 探测", "requestId": f"nostep-{SUFFIX}"}, bearer=TOKEN)
    check("无 X-Step-Up-Token → 403 A0403", st == 403 and r.get("code") == "A0403", f"{st} {r}")

    # 前置：凭证齐全且 Σ借方 == 金额（COMBO 逐腿归集）
    legs = p.get("legs") or []
    if legs:
        items = []
        for lg in legs:
            items += entries(lg["id"])
        voucher_biz = ",".join(lg["id"] for lg in legs)
    else:
        items = entries(target)
        voucher_biz = target
    check("账务凭证齐全且 Σ借方 == 支付金额", debit_sum(items) == p.get("amount"),
          f"Σ借方={debit_sum(items)} amount={p.get('amount')}")
    st, tr = api("GET", f"/api/accounting/transfers/{target}")
    if legs:
        check("COMBO 主单无直接凭证属正常（按腿落账）", st == 404, f"{st}")
    else:
        check("主单凭证可查", st == 200, f"{st} {tr}")

    order = ok("GET", f"/api/orders/{p['orderId']}")
    check("订单 PAID 且指向本支付单",
          order.get("status") == "PAID" and order.get("paymentId") == target, str(order)[:200])

    print("== 2. 修复前资金快照（修复必须分毫不动）==")
    t0 = trial()
    before_bal = {}
    for it in items:
        aid = it.get("accountId")
        if aid and aid not in before_bal:
            before_bal[aid] = bal(aid)
    e0 = len(items)
    rep0, v0 = semantic_violations()
    check("修复前试算平衡", t0.get("balanced") is True and t0.get("balanceSum") == 0, str(t0)[:200])
    print(f"  修复前：D=C={t0.get('totalDebit')} 分录={t0.get('entries')} 语义违规={v0}")

    print("== 3. 双人复核 + 执行修复 ==")
    rid = f"repair-{SUFFIX}"
    st, r = api("POST", f"/api/payments/{target}/repair-terminal",
                {"reason": "依权威 dtx=CONFIRMED 修正历史竞态脏单（用户已拍板）", "requestId": rid}, step=True)
    review_id = None
    if st == 400 and isinstance(r, dict) and r.get("code") == "A0428":
        review_id = r.get("reviewId")
        check("大额触发双人复核 A0428", bool(review_id), str(r))
        bob = f"repairbob{SUFFIX}"
        st2, r2 = api("POST", "/api/console/users",
                      {"username": bob, "password": BOB_PW, "roles": ["OPERATOR"]})
        check("建第二审批人（OPERATOR）", st2 == 200, f"{st2} {r2}")
        btk = login(bob, BOB_PW)
        bst, _ = step_up(btk, BOB_PW)
        st3, r3 = http("POST", f"/api/reviews/{review_id}/approve", {}, bearer=btk, step=bst)
        check("第二人批准复核（submitter≠approver）",
              st3 == 200 and r3.get("status") == "APPROVED"
              and r3.get("submitter") != r3.get("approver"), f"{st3} {r3}")
        STEP, STEP_EXP = step_up(TOKEN, ADMIN_PW)
        st, r = api("POST", f"/api/payments/{target}/repair-terminal",
                    {"reason": "依权威 dtx=CONFIRMED 修正历史竞态脏单（用户已拍板）", "requestId": rid}, step=True)
    check("修复受理成功 200", st == 200, f"{st} {r}")
    if st == 200:
        check("返回 before=FAILED / after=SUCCESS",
              r.get("before") == "FAILED" and r.get("after") == "SUCCESS", str(r)[:250])
        check("返回权威依据 xid", r.get("authorityXid") == xid, str(r.get("authorityXid")))

    print("== 4. 修复后不变量（资金分毫未动）==")
    p2 = ok("GET", f"/api/payments/{target}")
    check("支付单已修正为 SUCCESS", p2.get("status") == "SUCCESS", str(p2.get("status")))
    o2 = ok("GET", f"/api/orders/{p['orderId']}")
    check("订单仍 PAID 且指向本单", o2.get("status") == "PAID" and o2.get("paymentId") == target, str(o2)[:200])
    if legs:
        items2 = []
        for lg in legs:
            items2 += entries(lg["id"])
    else:
        items2 = entries(target)
    check("分录条数不变（未新增/删除凭证）", len(items2) == e0, f"{len(items2)} vs {e0}")
    check("Σ借方不变", debit_sum(items2) == debit_sum(items), f"{debit_sum(items2)} vs {debit_sum(items)}")
    same = all(bal(a) == b for a, b in before_bal.items())
    check("涉及账户余额逐个不变", same,
          str({a: (b, bal(a)) for a, b in before_bal.items() if bal(a) != b}))
    t1 = trial()
    check("试算 D=C 与修复前完全一致",
          t1.get("totalDebit") == t0.get("totalDebit")
          and t1.get("totalCredit") == t0.get("totalCredit")
          and t1.get("balanceSum") == 0, f"{t0.get('totalDebit')} → {t1.get('totalDebit')}")
    rep1, v1 = semantic_violations()
    check("对账六项全 pass", rep1.get("pass") is True,
          str([c for c in rep1.get("checks", []) if not c.get("pass")])[:300])
    check("「FAILED 单已动账未冲正」计数减 1", v1 == max(0, v0 - 1), f"{v0} → {v1}")

    print("== 5. 幂等与非法路径 ==")
    st, r = api("POST", f"/api/payments/{target}/repair-terminal",
                {"reason": "重复修复", "requestId": rid}, step=True)
    check("重复修复幂等（不重复翻状态/不重复审计）",
          st in (200, 409) and (st == 409 or r.get("repaired") is False), f"{st} {r}")
    p3 = ok("GET", f"/api/payments/{target}")
    check("重复修复后仍 SUCCESS", p3.get("status") == "SUCCESS", str(p3.get("status")))
    # 已 SUCCESS 的单不允许再修复（只允许 FAILED→SUCCESS）
    succ = next((x for x in ok("GET", "/api/payments")
                 if x.get("status") == "SUCCESS" and x.get("id") != target), None)
    if succ:
        st, r = api("POST", f"/api/payments/{succ['id']}/repair-terminal",
                    {"reason": "非法：已 SUCCESS", "requestId": f"bad-{SUFFIX}"}, step=True)
        check("已 SUCCESS 单拒绝修复 409", st == 409, f"{st} {r}")
    # dtx 非 CONFIRMED 的 FAILED 单必须拒绝
    cand = next((x for x in ok("GET", "/api/payments")
                 if x.get("status") == "FAILED" and x.get("id") != target and not entries(x["id"])), None)
    if cand:
        st, r = api("POST", f"/api/payments/{cand['id']}/repair-terminal",
                    {"reason": "非法：无凭证/dtx 非 CONFIRMED", "requestId": f"bad2-{SUFFIX}"}, step=True)
        check("无凭证的 FAILED 单拒绝修复 409", st == 409, f"{st} {r}")
        check("拒绝后该单仍 FAILED", ok("GET", f"/api/payments/{cand['id']}").get("status") == "FAILED")
    else:
        skip("无凭证 FAILED 单拒绝", "库中无此类样本")

    print("== 6. 审计留痕 ==")
    au = ok("GET", f"/api/audit?size=200")
    rows = au.get("items") or au.get("records") or []
    hit = [x for x in rows if "repair-terminal" in str(x.get("path", ""))]
    check("审计含 repair-terminal 操作记录", len(hit) >= 1, f"命中 {len(hit)}")
    if hit:
        h = hit[0]
        check("审计含操作人与结果", bool(h.get("username") or h.get("operator")) and bool(h.get("result")), str(h)[:200])

    print(f"\n结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())